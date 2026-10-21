package com.str.backend.egop;

import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgopAktDispatcherTest {

    private static final String RN = "HR180000123456789001";

    private EgopFilingStore store;
    private LessorRepository lessorRepository;
    private EgopFilingService filingService;
    private EgopAktKorisnik korisnik;
    private StrDocumentService documentService;
    private RnLifecycleLookup lookup;
    private EgopAktDispatcher dispatcher;
    private UUID logId;

    private EgopPismenoEntity akt;
    private SubmissionEntity submission;
    private LessorEntity lessor;

    @BeforeEach
    void setUp() {
        store = mock(EgopFilingStore.class);
        lessorRepository = mock(LessorRepository.class);
        filingService = mock(EgopFilingService.class);
        korisnik = mock(EgopAktKorisnik.class);
        documentService = mock(StrDocumentService.class);
        lookup = mock(RnLifecycleLookup.class);
        dispatcher = new EgopAktDispatcher(store, lessorRepository, filingService,
                new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2)), korisnik,
                documentService, lookup);
        logId = UUID.randomUUID();

        lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb",
                "ana@example.com");
        submission = SubmissionEntity.create(null, lessor.getLessorId(), null, null, null, null);
        akt = EgopPismenoEntity.forAct(submission.getSubmissionId(), RN, logId.toString(),
                "Obavijest o suspenziji registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, "pdf".getBytes());

        when(store.findAkt(akt.getId())).thenReturn(Optional.of(akt));
        when(store.findSubmission(submission.getSubmissionId())).thenReturn(Optional.of(submission));
        when(lessorRepository.findById(lessor.getLessorId())).thenReturn(Optional.of(lessor));
        when(korisnik.za(akt)).thenReturn("ana.sluzbenica");
        when(store.markAktFailed(any(), any(), any())).thenAnswer(inv -> {
            akt.markFailed(inv.getArgument(1), inv.getArgument(2));
            return akt.getSyncAttempts();
        });
    }

    @Test
    void dispatch_filesAct() throws Exception {
        dispatcher.dispatch(akt.getId());

        verify(filingService).fileAct(eq(akt), eq(submission), eq(lessor), eq("ana.sluzbenica"), any());
    }

    /** Retry job zove dispatch ponovo — već urudžbiran akt se ne smije poslati dvaput. */
    @Test
    void dispatch_alreadySynced_skips() throws Exception {
        akt.markSynced();

        dispatcher.dispatch(akt.getId());

        verify(filingService, never()).fileAct(any(), any(), any(), any(), any());
    }

    @Test
    void dispatch_filingFails_marksFailedWithBackoff() throws Exception {
        doThrow(new EgopBadRequestException("eGOP down"))
                .when(filingService).fileAct(any(), any(), any(), any(), any());

        dispatcher.dispatch(akt.getId());

        assertThat(akt.getStatus()).isEqualTo(EgopSyncStatus.FAILED);
        assertThat(akt.getSyncAttempts()).isEqualTo(1);
        assertThat(akt.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofSeconds(90)));
        assertThat(akt.getSyncError()).isEqualTo("eGOP down");
    }

    /** Pad urudžbe ne smije rušiti pozivatelja — RB je valjan neovisno o uredskom poslovanju. */
    @Test
    void dispatch_unexpectedError_isContained() throws Exception {
        doThrow(new IllegalStateException("boom"))
                .when(filingService).fileAct(any(), any(), any(), any(), any());

        dispatcher.dispatch(akt.getId());

        assertThat(akt.getStatus()).isEqualTo(EgopSyncStatus.FAILED);
    }

    @Test
    void dispatch_missingSubmission_skips() throws Exception {
        when(store.findSubmission(any())).thenReturn(Optional.empty());

        dispatcher.dispatch(akt.getId());

        verify(filingService, never()).fileAct(any(), any(), any(), any(), any());
    }

    @Test
    void dispatch_unknownAkt_skips() throws Exception {
        dispatcher.dispatch(UUID.randomUUID());

        verify(filingService, never()).fileAct(any(), any(), any(), any(), any());
    }

    // --- konačni dokument ------------------------------------------------------------------

    /** Dok je prijelaz zadnji za RB, akt se renderira s klasom predmeta akata i urbrojem. */
    @Test
    void finalDocument_latestTransition_isRenderedWithFiling() throws Exception {
        FilingReference filing = new FilingReference("334-06/11-26/77", "529-06-03/01-27-5-1", 1003);
        when(lookup.findEvent(logId)).thenReturn(Optional.of(event()));
        when(lookup.isLatestTransition(RN, logId)).thenReturn(true);
        when(documentService.render(StrDocumentType.SUSPENZIJA, RN, "razlog", filing))
                .thenReturn("konacni".getBytes());

        assertThat(finalDocument(filing)).contains("konacni".getBytes());
    }

    /** Noviji prijelaz je promijenio stanje RB-a — vrijedi snimak iz trenutka događaja. */
    @Test
    void finalDocument_supersededTransition_keepsSnapshot() throws Exception {
        when(lookup.findEvent(logId)).thenReturn(Optional.of(event()));
        when(lookup.isLatestTransition(RN, logId)).thenReturn(false);

        assertThat(finalDocument(new FilingReference("k", "u", 1))).isEmpty();
        verify(documentService, never()).render(any(), any(), any(), any());
    }

    /** Pad rendera ne zaustavlja urudžbu — prilaže se snimak. */
    @Test
    void finalDocument_renderFails_keepsSnapshot() throws Exception {
        when(lookup.findEvent(logId)).thenReturn(Optional.of(event()));
        when(lookup.isLatestTransition(RN, logId)).thenReturn(true);
        when(documentService.render(any(), any(), any(), any())).thenThrow(new IllegalStateException("predložak"));

        assertThat(finalDocument(new FilingReference("k", "u", 1))).isEmpty();
    }

    private Optional<byte[]> finalDocument(FilingReference filing) throws Exception {
        dispatcher.dispatch(akt.getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<EgopFilingService.AktDokument> dokument = ArgumentCaptor.forClass(EgopFilingService.AktDokument.class);
        verify(filingService).fileAct(eq(akt), eq(submission), eq(lessor), eq("ana.sluzbenica"), dokument.capture());
        return dokument.getValue().konacni(filing);
    }

    private RnLifecycleEvent event() {
        return new RnLifecycleEvent(logId, RN, RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED,
                RnTrigger.DEADLINE_EXCEEDED, "SYSTEM", "razlog");
    }
}
