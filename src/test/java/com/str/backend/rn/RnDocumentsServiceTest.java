package com.str.backend.rn;

import com.str.backend.attachment.PrilogDto;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.attachment.TestPrilog;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.objection.SuspensionObjectionEntity;
import com.str.backend.objection.SuspensionObjectionFileDto;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.dto.RnDocumentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private PrilogService prilogService;
    private SuspensionObjectionRepository objectionRepository;
    private com.str.backend.objection.PodnesakPdfService podnesakPdf;
    private RnDocumentsService service;

    private UUID submissionId;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        egopPismenoRepository = mock(EgopPismenoRepository.class);
        prilogService = mock(PrilogService.class);
        objectionRepository = mock(SuspensionObjectionRepository.class);
        podnesakPdf = mock(com.str.backend.objection.PodnesakPdfService.class);
        service = new RnDocumentsService(rnRepository, submissionRepository, egopPismenoRepository, prilogService,
                objectionRepository, podnesakPdf, true);
        submissionId = UUID.randomUUID();
    }

    /** Varijanta s ugašenim prikazom podneska ({@code str.rn.documents.zahtjev-visible=false}). */
    private RnDocumentsService serviceWithoutZahtjev() {
        return new RnDocumentsService(rnRepository, submissionRepository, egopPismenoRepository, prilogService,
                objectionRepository, podnesakPdf, false);
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

    /**
     * Zastavica skriva podnesak iz popisa, ali ga ne ukida: bajtovi se i dalje serviraju.
     * Obje polovice te odluke stoje u jednom testu, da ih se ne može razdvojiti nehotice.
     */
    @Test
    void listForRn_zahtjevHidden_onlyDodjelaButPdfStillServed() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission("pdf".getBytes(), Instant.now().minus(3, ChronoUnit.DAYS));
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN)).thenReturn(List.of());

        RnDocumentsService hidden = serviceWithoutZahtjev();

        assertThat(hidden.listForRn(RN)).extracting(RnDocumentDto::slug).containsExactly("dodjela");
        assertThat(hidden.zahtjevPdf(RN)).isEqualTo("pdf".getBytes());
    }

    /**
     * T17: zahtjev nosi priloge zahtjeva, a pismeno prijelaza priloge tog prijelaza — ulazno (podnesak
     * stranke) samo njezine, izlazni akt samo priloge službenika.
     */
    @Test
    void listForRn_attachments_listedUnderTheirPismeno() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission("pdf".getBytes(), Instant.now().minus(3, ChronoUnit.DAYS));
        UUID logId = UUID.randomUUID();
        EgopPismenoEntity opozivZahtjev = EgopPismenoEntity.forAct(submissionId, RN, logId.toString(),
                "Obavijest o opozivu registracijskog broja", EgopPismenoEntity.Smjer.ULAZNO, new byte[]{1});
        EgopPismenoEntity opozivAkt = EgopPismenoEntity.forAct(submissionId, RN, logId.toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{2});
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN)).thenReturn(List.of(opozivZahtjev, opozivAkt));
        when(prilogService.zaVlasnika(PrilogVlasnik.zahtjev(submissionId))).thenReturn(List.of(
                TestPrilog.vezan(7, VrstaVlasnika.SUBMISSION, submissionId, "tlocrt.pdf", "NIAS:12345678903")));
        when(prilogService.zaVlasnike(VrstaVlasnika.RN_LOG, List.of(logId, logId))).thenReturn(Map.of(logId, List.of(
                TestPrilog.vezan(8, VrstaVlasnika.RN_LOG, logId, "stranka.pdf", "NIAS:12345678903"),
                TestPrilog.vezan(9, VrstaVlasnika.RN_LOG, logId, "sluzbenik.pdf", "INTERNAL:98765432106"))));

        List<RnDocumentDto> docs = service.listForRn(RN);

        assertThat(docs.get(0).prilozi()).extracting(PrilogDto::naziv).containsExactly("tlocrt.pdf");
        assertThat(docs.get(0).prilozi().get(0).href()).isEqualTo("/api/rn/" + RN + "/attachments/7");
        assertThat(docs.get(1).prilozi()).isEmpty(); // obavijest o dodjeli
        assertThat(docs.get(2).prilozi()).extracting(PrilogDto::naziv).containsExactly("stranka.pdf");
        assertThat(docs.get(3).prilozi()).extracting(PrilogDto::naziv).containsExactly("sluzbenik.pdf");
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
     * Očitovanje ili prigovor ulazi u „Dokumente" tek kad je urudžbiran (ima URBROJ): eGOP-ov u postupku
     * MINTS-a i ručni u županijskom. Dokument je pohranjeni PDF podneska ({@code /documents/pohranjeno/{id}}),
     * a prilozi dokument koji je stranka učitala ({@code /documents/podnesak/{id}}) pa njezine ostale
     * datoteke. Neurudžbiran podnesak se ne vidi. Zapisan zadnji, stoji zadnji.
     */
    @Test
    void listForRn_registeredPodnesak_listedLast_withStoredPdf_andUploadedDocumentFirstAmongAttachments() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission("pdf".getBytes(), Instant.now().minus(3, ChronoUnit.DAYS));
        EgopPismenoEntity suspenzija = EgopPismenoEntity.forAct(submissionId, RN, "log1",
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{1});
        UUID neurudzbiranId = UUID.randomUUID();
        EgopPismenoEntity neurudzbiran = EgopPismenoEntity.forPodnesak(submissionId, RN, neurudzbiranId,
                StrDocumentType.OCITOVANJE_SUSPENZIJE.vrstaPismenaNaziv());
        UUID mintsId = UUID.randomUUID();
        EgopPismenoEntity mints = EgopPismenoEntity.forPodnesak(submissionId, RN, mintsId,
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv());
        mints.applyPismeno(1009, "529-06/26-4", "108", false);
        mints.replacePdf("%PDF-podnesak".getBytes());
        mints.markDocumentAttached();
        UUID zupanijaId = UUID.randomUUID();
        EgopPismenoEntity zupanija = EgopPismenoEntity.rucniPodnesak(submissionId, RN, zupanijaId,
                StrDocumentType.PRIGOVOR_POVLACENJA.vrstaPismenaNaziv(), "UP/I-334-06/26-01/7", "2181-26-3",
                new byte[]{9});
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN))
                .thenReturn(List.of(suspenzija, neurudzbiran, mints, zupanija));
        when(prilogService.zaVlasnike(VrstaVlasnika.OBJECTION, List.of(mintsId, zupanijaId))).thenReturn(Map.of(
                zupanijaId, List.of(TestPrilog.vezan(31, VrstaVlasnika.OBJECTION, zupanijaId, "dokaz.png",
                        "NIAS:12345678903"))));
        SuspensionObjectionRepository.ObjectionSummary zupanijaPrigovor = summary(zupanijaId, "prigovor.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 2048);
        SuspensionObjectionRepository.ObjectionSummary mintsPrigovor = summary(mintsId, "ocitovanje.pdf",
                "application/pdf", 1024);
        when(objectionRepository.findSummariesByRn(RN)).thenReturn(List.of(zupanijaPrigovor, mintsPrigovor));

        List<RnDocumentDto> docs = service.listForRn(RN);

        assertThat(docs).extracting(RnDocumentDto::slug).containsExactly(
                "zahtjev", "dodjela", "suspenzija", "prigovor-suspenzije", "prigovor-povlacenja");
        assertThat(docs).extracting(RnDocumentDto::id).doesNotContain(neurudzbiran.getId());
        RnDocumentDto zadnji = docs.get(docs.size() - 1);
        assertThat(zadnji.id()).isEqualTo(zupanija.getId());
        assertThat(zadnji.naziv()).isEqualTo(StrDocumentType.PRIGOVOR_POVLACENJA.vrstaPismenaNaziv());
        assertThat(zadnji.smjer()).isEqualTo("ULAZNO");
        assertThat(zadnji.izdano()).isEqualTo(LocalDate.now(com.str.backend.common.Zagreb.ZONE));
        assertThat(zadnji.href()).isEqualTo("/api/rn/" + RN + "/documents/pohranjeno/" + zupanija.getId());
        assertThat(zadnji.prilozi()).extracting(PrilogDto::naziv).containsExactly("prigovor.docx", "dokaz.png");
        assertThat(zadnji.prilozi().get(0).href()).isEqualTo("/api/rn/" + RN + "/documents/podnesak/" + zupanijaId);
        assertThat(zadnji.prilozi().get(0).id()).isNull();
        assertThat(zadnji.prilozi().get(0).velicina()).isEqualTo(2048);
        assertThat(zadnji.prilozi().get(0).contentType()).startsWith("application/vnd.openxmlformats");
        assertThat(zadnji.prilozi().get(1).href()).isEqualTo("/api/rn/" + RN + "/attachments/31");
        assertThat(docs.get(3).href()).isEqualTo("/api/rn/" + RN + "/documents/pohranjeno/" + mints.getId());
        assertThat(docs.get(3).prilozi()).extracting(PrilogDto::naziv).containsExactly("ocitovanje.pdf");
    }

    /**
     * Staro pismeno MINTS-a (urudžbirano prije PDF-a podneska): u eGOP-u mu je glavni dokument izvornik stranke,
     * pa ga i STR nudi kao dokument ({@code /documents/podnesak/{id}}), bez generiranja PDF-a — ni u popisu ni
     * pri dohvatu po id-u.
     */
    @Test
    void oldMintsPodnesakWithoutPdf_keepsUploadedDocumentAsMain_andIsNeverRendered() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission(null, Instant.now().minus(3, ChronoUnit.DAYS));
        UUID objectionId = UUID.randomUUID();
        EgopPismenoEntity stari = EgopPismenoEntity.forPodnesak(submissionId, RN, objectionId,
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv());
        stari.applyPismeno(1009, "529-06/26-4", "108", false);
        stari.markDocumentAttached();
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN)).thenReturn(List.of(stari));
        when(prilogService.zaVlasnike(VrstaVlasnika.OBJECTION, List.of(objectionId))).thenReturn(Map.of(objectionId,
                List.of(TestPrilog.vezan(51, VrstaVlasnika.OBJECTION, objectionId, "dokaz.png", "NIAS:12345678903"))));
        SuspensionObjectionRepository.ObjectionSummary prigovor = summary(objectionId, "prigovor.pdf",
                "application/pdf", 100);
        when(objectionRepository.findSummariesByRn(RN)).thenReturn(List.of(prigovor));
        when(egopPismenoRepository.findById(stari.getId())).thenReturn(Optional.of(stari));

        RnDocumentDto doc = service.listForRn(RN).get(1);

        assertThat(doc.id()).isEqualTo(stari.getId());
        assertThat(doc.href()).isEqualTo("/api/rn/" + RN + "/documents/podnesak/" + objectionId);
        assertThat(doc.prilozi()).extracting(PrilogDto::naziv).containsExactly("dokaz.png");
        assertThrows(ResourceNotFoundException.class, () -> service.storedAktPdf(RN, stari.getId()));
        org.mockito.Mockito.verifyNoInteractions(podnesakPdf);
        org.mockito.Mockito.verify(egopPismenoRepository, org.mockito.Mockito.never())
                .upisiPdfAkoNema(any(), any(), any());
    }

    /**
     * Istodobna dva dohvata ručnog pismena bez PDF-a: drugi izgubi uvjetni upis (0 redaka) i vraća PDF koji je
     * spremio prvi, ne svoj render — svaki dohvat je isti dokument.
     */
    @Test
    void storedAktPdf_lazyRenderRace_returnsStoredBytes() {
        EgopPismenoEntity stari = EgopPismenoEntity.rucniPodnesak(submissionId, RN, UUID.randomUUID(),
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv(), "UP/I-334-06/26-01/7", "2181-26-3", null);
        when(egopPismenoRepository.findById(stari.getId())).thenReturn(Optional.of(stari));
        when(podnesakPdf.zaRucnoPismeno(stari)).thenReturn("%PDF-moj".getBytes());
        when(egopPismenoRepository.upisiPdfAkoNema(eq(stari.getId()), any(), any())).thenReturn(0);
        when(egopPismenoRepository.findPdfContent(stari.getId())).thenReturn("%PDF-prvi".getBytes());

        assertArrayEquals("%PDF-prvi".getBytes(), service.storedAktPdf(RN, stari.getId()).pdf());
    }

    /**
     * Ručno urudžbirani podnesak zapisan prije PDF-a podneska: prvi dohvat izvodi PDF iz retka i sprema ga
     * (uvjetno), pa se vraća on; pismeno koje PDF ima vraća se kakvo jest, bez rendera.
     */
    @Test
    void storedAktPdf_registeredPodnesakWithoutPdf_isRenderedLazilyAndStored() {
        EgopPismenoEntity stari = EgopPismenoEntity.rucniPodnesak(submissionId, RN, UUID.randomUUID(),
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv(), "UP/I-334-06/26-01/7", "2181-26-3", null);
        when(egopPismenoRepository.findById(stari.getId())).thenReturn(Optional.of(stari));
        when(podnesakPdf.zaRucnoPismeno(stari)).thenReturn("%PDF-naknadno".getBytes());
        when(egopPismenoRepository.upisiPdfAkoNema(eq(stari.getId()), any(), any())).thenReturn(1);

        RnDocumentsService.StoredDocument doc = service.storedAktPdf(RN, stari.getId());

        assertArrayEquals("%PDF-naknadno".getBytes(), doc.pdf());
        assertEquals("prigovor-suspenzije-" + RN + ".pdf", doc.filename());
        org.mockito.Mockito.verify(egopPismenoRepository).upisiPdfAkoNema(eq(stari.getId()),
                eq("%PDF-naknadno".getBytes()), any());

        EgopPismenoEntity novi = EgopPismenoEntity.rucniPodnesak(submissionId, RN, UUID.randomUUID(),
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv(), "K", "U-1", "%PDF-pohranjeni".getBytes());
        when(egopPismenoRepository.findById(novi.getId())).thenReturn(Optional.of(novi));
        assertArrayEquals("%PDF-pohranjeni".getBytes(), service.storedAktPdf(RN, novi.getId()).pdf());
        org.mockito.Mockito.verify(podnesakPdf, org.mockito.Mockito.never()).zaRucnoPismeno(novi);

        // Neurudžbiran podnesak (MINTS, eGOP još nije vratio URBROJ) nema dokument: 404, bez rendera.
        EgopPismenoEntity neurudzbiran = EgopPismenoEntity.forPodnesak(submissionId, RN, UUID.randomUUID(),
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv());
        when(egopPismenoRepository.findById(neurudzbiran.getId())).thenReturn(Optional.of(neurudzbiran));
        assertThrows(ResourceNotFoundException.class, () -> service.storedAktPdf(RN, neurudzbiran.getId()));
        org.mockito.Mockito.verify(podnesakPdf, org.mockito.Mockito.never()).zaRucnoPismeno(neurudzbiran);
    }

    private static SuspensionObjectionRepository.ObjectionSummary summary(UUID id, String fileName, String contentType,
                                                                         long size) {
        SuspensionObjectionRepository.ObjectionSummary s = mock(SuspensionObjectionRepository.ObjectionSummary.class);
        when(s.getObjectionId()).thenReturn(id);
        when(s.getFileName()).thenReturn(fileName);
        when(s.getContentType()).thenReturn(contentType);
        when(s.getFileSize()).thenReturn(size);
        return s;
    }

    /**
     * Datum u „Dokumentima" je hrvatski dan, kao {@code urudzbiranje.datum} na prigovoru: pismeno zapisano u
     * 23:30 UTC (01:30 u Zagrebu ljeti, 00:30 zimi) je sljedeći dan.
     */
    @Test
    void listForRn_izdano_isZagrebDay() {
        stubRn(submissionId, LocalDate.of(2026, 7, 1));
        stubSubmission(null, Instant.parse("2026-07-01T10:00:00Z"));
        EgopPismenoEntity podnesak = EgopPismenoEntity.rucniPodnesak(submissionId, RN, UUID.randomUUID(),
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv(), "UP/I-334-06/26-01/7", "2181-26-3", new byte[]{9});
        org.springframework.test.util.ReflectionTestUtils.setField(podnesak, "createdAt",
                Instant.parse("2026-07-14T23:30:00Z"));
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN)).thenReturn(List.of(podnesak));

        assertThat(service.listForRn(RN)).last()
                .satisfies(d -> assertThat(d.izdano()).isEqualTo(LocalDate.of(2026, 7, 15)));
    }

    /** Dokument podneska je datoteka s prigovora; neurudžbiran ili tuđi podnesak je 404. */
    @Test
    void podnesak_registered_returnsObjectionFile_unregisteredOrForeign_404() {
        SuspensionObjectionEntity objection = SuspensionObjectionEntity.createForOib("99999999990", RN,
                RnStatus.SUSPENDED, UUID.randomUUID(), "NIAS:99999999990", "prigovor.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[]{0x50, 0x4B});
        UUID id = objection.getObjectionId();
        EgopPismenoEntity pismeno = EgopPismenoEntity.rucniPodnesak(submissionId, RN, id,
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv(), "UP/I-334-06/26-01/7", "2181-26-3", new byte[]{9});
        when(egopPismenoRepository.findFirstByRnAndActRefAndUrBrojIsNotNull(RN, "PRIGOVOR:" + id))
                .thenReturn(Optional.of(pismeno));
        when(objectionRepository.findByObjectionIdAndRn(id, RN)).thenReturn(Optional.of(objection));

        SuspensionObjectionFileDto file = service.podnesak(RN, id);

        assertThat(file.fileName()).isEqualTo("prigovor.docx");
        assertThat(file.contentType()).isEqualTo(objection.getContentType());
        assertArrayEquals(new byte[]{0x50, 0x4B}, file.content());
        UUID neurudzbiran = UUID.randomUUID();
        assertThrows(ResourceNotFoundException.class, () -> service.podnesak(RN, neurudzbiran));
        assertThrows(ResourceNotFoundException.class, () -> service.podnesak("HR180000123456789999", id));
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

    /**
     * Sekcija „Dokumenti" na detalju RB-a mora nositi svaki akt koji nastaje iz prijelaza
     * statusa. Vrste se izvode iz {@link StrDocumentType#pismenaForTransition}, pa nova vrsta akta
     * automatski ulazi u provjeru; popis šest obavijesti i zahtjeva za opoziv brani da neki ne
     * ispadne iz mapiranja.
     */
    @Test
    void listForRn_allLifecycleNotices_listedAfterZahtjevAndDodjela() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission("pdf".getBytes(), Instant.now().minus(3, ChronoUnit.DAYS));
        Set<StrDocumentType> lifecycleTypes = new LinkedHashSet<>();
        for (RnStatus to : RnStatus.values()) {
            for (RnTrigger trigger : RnTrigger.values()) {
                lifecycleTypes.addAll(StrDocumentType.pismenaForTransition(to, trigger));
            }
        }
        assertThat(lifecycleTypes).containsExactlyInAnyOrder(
                StrDocumentType.PRIJEDLOG_SUSPENZIJE, StrDocumentType.OBUSTAVA_SUSPENZIJE,
                StrDocumentType.SUSPENZIJA, StrDocumentType.REAKTIVACIJA,
                StrDocumentType.POVLACENJE, StrDocumentType.ZAHTJEV_OPOZIVA, StrDocumentType.OPOZIV,
                StrDocumentType.PRIJEDLOG_POVLACENJA, StrDocumentType.OBUSTAVA_POVLACENJA,
                StrDocumentType.REAKTIVACIJA_POVLACENJA);

        List<EgopPismenoEntity> akti = new ArrayList<>();
        for (StrDocumentType type : lifecycleTypes) {
            akti.add(EgopPismenoEntity.forAct(submissionId, RN, "log-" + type.slug(),
                    type.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.valueOf(type.smjer().name()),
                    new byte[]{1}));
        }
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN)).thenReturn(akti);

        List<RnDocumentDto> docs = service.listForRn(RN);

        List<String> expectedSlugs = new ArrayList<>(List.of("zahtjev", "dodjela"));
        lifecycleTypes.forEach(t -> expectedSlugs.add(t.slug()));
        assertThat(docs).extracting(RnDocumentDto::slug).containsExactlyElementsOf(expectedSlugs);
        for (int i = 0; i < akti.size(); i++) {
            EgopPismenoEntity akt = akti.get(i);
            RnDocumentDto doc = docs.get(i + 2);
            assertThat(doc.id()).isEqualTo(akt.getId());
            assertThat(doc.naziv()).isEqualTo(akt.getVrstaPismenaNaziv());
            assertThat(doc.smjer()).isEqualTo(akt.getSmjer().name());
            assertThat(doc.href()).isEqualTo("/api/rn/" + RN + "/documents/pohranjeno/" + akt.getId());
        }
    }

    /**
     * Opoziv: zahtjev (ulazno) i obavijest (izlazno) su dva retka s vlastitim slugom; pečaćena
     * obavijest se preuzima pohranjena, dakle s pečatom (applySeal zamjenjuje pdf_content).
     */
    @Test
    void listForRn_revocation_listsRequestAndSealedNotice() {
        stubRn(submissionId, LocalDate.now().minusDays(2));
        stubSubmission("pdf".getBytes(), Instant.now().minus(3, ChronoUnit.DAYS));
        EgopPismenoEntity zahtjev = EgopPismenoEntity.forAct(submissionId, RN, "log-1",
                StrDocumentType.ZAHTJEV_OPOZIVA.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.ULAZNO, new byte[]{1});
        EgopPismenoEntity obavijest = EgopPismenoEntity.forAct(submissionId, RN, "log-1",
                StrDocumentType.OPOZIV.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{2});
        obavijest.applySeal("%PDF-pecaceni".getBytes(), UUID.randomUUID(), "7K3M", "B-LT", "sha-256=:x:");
        when(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN)).thenReturn(List.of(zahtjev, obavijest));
        when(egopPismenoRepository.findById(obavijest.getId())).thenReturn(java.util.Optional.of(obavijest));

        List<RnDocumentDto> docs = service.listForRn(RN);

        assertThat(docs).extracting(RnDocumentDto::slug)
                .containsExactly("zahtjev", "dodjela", "zahtjev-opoziva", "opoziv");
        assertThat(docs).extracting(RnDocumentDto::smjer).endsWith("ULAZNO", "IZLAZNO");
        RnDocumentsService.StoredDocument preuzeto = service.storedAktPdf(RN, obavijest.getId());
        assertThat(preuzeto.pdf()).isEqualTo("%PDF-pecaceni".getBytes());
        assertThat(preuzeto.filename()).isEqualTo("opoziv-" + RN + ".pdf");
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
                1002, "529-06/26-2", "57", true);
        when(egopPismenoRepository.findBySubmissionIdAndVrstaPismenaNazivAndActRef(
                eq(submissionId), eq(StrDocumentType.DODJELA.vrstaPismenaNaziv()),
                eq(EgopPismenoEntity.ACT_REF_REGISTRACIJA)))
                .thenReturn(Optional.of(izlazno));

        FilingReference filing = service.dodjelaFiling(RN);
        assertEquals("334-01/26-01/55", filing.klasa());
        assertEquals("529-06/26-2", filing.urBroj());
        // JOP ide u desni kut zaglavlja („P/1002") — isti kao na verziji priloženoj eGOP-u.
        assertEquals(1002, filing.jop());
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
        assertThat(filing.jop()).isNull();
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
