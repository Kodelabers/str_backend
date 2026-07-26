package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private EgopAktDispatcher dispatcher;

    private EgopPismenoEntity akt;
    private SubmissionEntity submission;
    private LessorEntity lessor;

    @BeforeEach
    void setUp() {
        store = mock(EgopFilingStore.class);
        lessorRepository = mock(LessorRepository.class);
        filingService = mock(EgopFilingService.class);
        dispatcher = new EgopAktDispatcher(store, lessorRepository, filingService,
                new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2)));

        lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb",
                "ana@example.com");
        submission = SubmissionEntity.create(null, lessor.getLessorId(), null, null, null, null);
        akt = EgopPismenoEntity.forAct(submission.getSubmissionId(), RN, UUID.randomUUID().toString(),
                "Obavijest o suspenziji registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, "pdf".getBytes());

        when(store.findAkt(akt.getId())).thenReturn(Optional.of(akt));
        when(store.findSubmission(submission.getSubmissionId())).thenReturn(Optional.of(submission));
        when(lessorRepository.findById(lessor.getLessorId())).thenReturn(Optional.of(lessor));
        when(store.markAktFailed(any(), any(), any())).thenAnswer(inv -> {
            akt.markFailed(inv.getArgument(1), inv.getArgument(2));
            return akt.getSyncAttempts();
        });
    }

    @Test
    void dispatch_filesAct() throws Exception {
        dispatcher.dispatch(akt.getId());

        verify(filingService).fileAct(akt, submission, lessor);
    }

    /** Retry job zove dispatch ponovo — već urudžbiran akt se ne smije poslati dvaput. */
    @Test
    void dispatch_alreadySynced_skips() throws Exception {
        akt.markSynced();

        dispatcher.dispatch(akt.getId());

        verify(filingService, never()).fileAct(any(), any(), any());
    }

    @Test
    void dispatch_filingFails_marksFailedWithBackoff() throws Exception {
        doThrow(new EgopBadRequestException("eGOP down"))
                .when(filingService).fileAct(any(), any(), any());

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
                .when(filingService).fileAct(any(), any(), any());

        dispatcher.dispatch(akt.getId());

        assertThat(akt.getStatus()).isEqualTo(EgopSyncStatus.FAILED);
    }

    @Test
    void dispatch_missingSubmission_skips() throws Exception {
        when(store.findSubmission(any())).thenReturn(Optional.empty());

        dispatcher.dispatch(akt.getId());

        verify(filingService, never()).fileAct(any(), any(), any());
    }

    @Test
    void dispatch_unknownAkt_skips() throws Exception {
        dispatcher.dispatch(UUID.randomUUID());

        verify(filingService, never()).fileAct(any(), any(), any());
    }
}
