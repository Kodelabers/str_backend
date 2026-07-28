package com.str.backend.rn;

import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.dto.RnDocumentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RnDocumentsServiceTest {

    private static final String RN = "HR180000123456789001";

    private RnRepository rnRepository;
    private SubmissionRepository submissionRepository;
    private EgopPismenoRepository egopPismenoRepository;
    private RnDocumentsService service;

    private UUID submissionId;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        egopPismenoRepository = mock(EgopPismenoRepository.class);
        service = new RnDocumentsService(rnRepository, submissionRepository, egopPismenoRepository);
        submissionId = UUID.randomUUID();
    }

    @Test
    void listForRn_submissionAndNoActs_zahtjevThenDodjela() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission("pdf".getBytes(), Instant.now().minus(3, ChronoUnit.DAYS));
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN)).thenReturn(List.of());

        List<RnDocumentDto> docs = service.listForRn(RN);

        assertThat(docs).extracting(RnDocumentDto::slug).containsExactly("zahtjev", "dodjela");
        assertThat(docs.get(0).smjer()).isEqualTo("ULAZNO");
        assertThat(docs.get(0).href()).isEqualTo("/api/rn/" + RN + "/documents/zahtjev");
        assertThat(docs.get(1).smjer()).isEqualTo("IZLAZNO");
        assertThat(docs.get(1).href()).isEqualTo("/api/rn/" + RN + "/documents/dodjela");
    }

    @Test
    void listForRn_lifecycleActs_appendedInDateOrderWithStoredHref() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission("pdf".getBytes(), Instant.now().minus(3, ChronoUnit.DAYS));
        EgopPismenoEntity suspenzija = EgopPismenoEntity.forAct(submissionId, RN, "log1",
                "Obavijest o suspenziji registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{1});
        EgopPismenoEntity reaktivacija = EgopPismenoEntity.forAct(submissionId, RN, "log2",
                "Obavijest o reaktivaciji registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{2});
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN))
                .thenReturn(List.of(suspenzija, reaktivacija));

        List<RnDocumentDto> docs = service.listForRn(RN);

        assertThat(docs).extracting(RnDocumentDto::slug)
                .containsExactly("zahtjev", "dodjela", "suspenzija", "reaktivacija");
        RnDocumentDto sus = docs.get(2);
        assertThat(sus.id()).isEqualTo(suspenzija.getId());
        assertThat(sus.href()).isEqualTo("/api/rn/" + RN + "/documents/pohranjeno/" + suspenzija.getId());
        assertThat(sus.naziv()).isEqualTo("Obavijest o suspenziji registracijskog broja");
    }

    /**
     * Akti suspenzijskog toka moraju stajati u istom popisu kao zahtjev i obavijest o dodjeli —
     * s vlastitim slugom, smjerom i linkom za preuzimanje. Uvjet je da postoji redak u
     * {@code egop_pismeno}; {@code RnLifecycleFilingListener} ga zato zapisuje i onda kad se akt
     * ne urudžbira (vrsta bez šifre u šifrarniku).
     */
    @Test
    void listForRn_suspensionFlowActs_areListedLikeZahtjevAndDodjela() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission("pdf".getBytes(), Instant.now().minus(3, ChronoUnit.DAYS));
        EgopPismenoEntity prijedlog = EgopPismenoEntity.forAct(submissionId, RN, "log1",
                "Obavijest o prijedlogu suspenzije registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{1});
        EgopPismenoEntity obustava = EgopPismenoEntity.forAct(submissionId, RN, "log2",
                "Obavijest o obustavi postupka suspenzije registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{2});
        EgopPismenoEntity opoziv = EgopPismenoEntity.forAct(submissionId, RN, "log3",
                "Obavijest o opozivu registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{3});
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN))
                .thenReturn(List.of(prijedlog, obustava, opoziv));

        List<RnDocumentDto> docs = service.listForRn(RN);

        assertThat(docs).extracting(RnDocumentDto::slug).containsExactly(
                "zahtjev", "dodjela", "prijedlog-suspenzije", "obustava-suspenzije", "opoziv");
        // Slug se izvodi iz naziva vrste pismena — bez toga fronta nema ikonu ni rutu.
        assertThat(docs).extracting(RnDocumentDto::slug).doesNotContainNull();
        assertThat(docs.get(2).href())
                .isEqualTo("/api/rn/" + RN + "/documents/pohranjeno/" + prijedlog.getId());
        assertThat(docs.get(3).naziv())
                .isEqualTo("Obavijest o obustavi postupka suspenzije registracijskog broja");
    }

    @Test
    void listForRn_noSubmission_onlyDodjela() {
        stubRn(null, LocalDate.now().minusDays(2));
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN)).thenReturn(List.of());

        List<RnDocumentDto> docs = service.listForRn(RN);

        assertThat(docs).extracting(RnDocumentDto::slug).containsExactly("dodjela");
    }

    @Test
    void storedAktPdf_matchingRn_returnsBytesAndReadableFilename_foreignRn_404() {
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(submissionId, RN, "log1",
                "Obavijest o suspenziji registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{7, 8});
        when(egopPismenoRepository.findById(akt.getId())).thenReturn(Optional.of(akt));

        RnDocumentsService.StoredDocument doc = service.storedAktPdf(RN, akt.getId());
        assertArrayEquals(new byte[]{7, 8}, doc.pdf());
        assertEquals("suspenzija-" + RN + ".pdf", doc.filename());

        assertThrows(ResourceNotFoundException.class,
                () -> service.storedAktPdf("HR180000123456789999", akt.getId()));
    }

    @Test
    void dodjelaFiling_resolvesKlasaFromSubmissionAndUrBrojFromIzlaznoPismeno() {
        stubRn(submissionId, LocalDate.now());
        SubmissionEntity submission = SubmissionEntity.create(null, UUID.randomUUID(), null, null, null, null);
        submission.applyEgopPredmet(2026, 55, "334-01/26-01/55");
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));

        EgopPismenoEntity izlazno = EgopPismenoEntity.create(submissionId,
                StrDocumentType.DODJELA.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2");
        when(egopPismenoRepository.findBySubmissionIdAndVrstaPismenaNazivAndActRef(
                eq(submissionId), eq(StrDocumentType.DODJELA.vrstaPismenaNaziv()),
                eq(EgopPismenoEntity.ACT_REF_REGISTRACIJA)))
                .thenReturn(Optional.of(izlazno));

        FilingReference filing = service.dodjelaFiling(RN);
        assertEquals("334-01/26-01/55", filing.klasa());
        assertEquals("529-06/26-2", filing.urBroj());
    }

    @Test
    void dodjelaFiling_noIzlaznoPismeno_urBrojNull_klasaOnly() {
        stubRn(submissionId, LocalDate.now());
        SubmissionEntity submission = SubmissionEntity.create(null, UUID.randomUUID(), null, null, null, null);
        submission.applyEgopPredmet(2026, 55, "334-01/26-01/55");
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(egopPismenoRepository.findBySubmissionIdAndVrstaPismenaNazivAndActRef(any(), any(), any()))
                .thenReturn(Optional.empty());

        FilingReference filing = service.dodjelaFiling(RN);
        assertEquals("334-01/26-01/55", filing.klasa());
        assertThat(filing.urBroj()).isNull();
    }

    @Test
    void zahtjevPdf_missingSubmissionPdf_throws() {
        stubRn(submissionId, LocalDate.now());
        stubSubmission(null, Instant.now());

        assertThrows(ResourceNotFoundException.class, () -> service.zahtjevPdf(RN));
    }

    private void stubRn(UUID subId, LocalDate issueDate) {
        RnEntity rn = RnEntity.issue(RN, subId, UUID.randomUUID(), issueDate);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
    }

    private void stubSubmission(byte[] pdf, Instant filingDate) {
        SubmissionEntity submission = SubmissionEntity.create(
                null, UUID.randomUUID(), null, filingDate, null, pdf);
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
    }
}
