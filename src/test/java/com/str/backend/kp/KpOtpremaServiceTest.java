package com.str.backend.kp;

import com.str.backend.document.DocumentLabels;
import com.str.backend.egop.EgopFilingStore;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopRetryPolicy;
import com.str.backend.kp.client.KpClient;
import com.str.backend.kp.client.KpOdgovor;
import com.str.backend.kp.client.KpPoruka;
import com.str.backend.kp.client.KpTransportException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KpOtpremaServiceTest {

    private static final String OIB = "12345678903";
    private static final String OIB_TVRTKE = "98765432106";
    private static final String RN = "HR012345678901234567";
    private static final String KLASA = "UP/I-334-01/26-01/55";
    private static final byte[] PDF = "%PDF-akt".getBytes();
    private static final KpPrimatelj FO = new KpPrimatelj(KpPrimatelj.Tip.FO, OIB, null);
    private static final KpPrimatelj PS = new KpPrimatelj(KpPrimatelj.Tip.PS, OIB_TVRTKE, "001");

    private KpClient client;
    private KpOtpremaStore store;
    private EgopFilingStore filingStore;
    private LessorRepository lessorRepository;
    private RnRepository rnRepository;
    private KpZamjenskaDostava zamjenska;
    private LessorEntity lessor;
    private UUID submissionId;

    @BeforeEach
    void setUp() {
        client = mock(KpClient.class);
        store = mock(KpOtpremaStore.class);
        filingStore = mock(EgopFilingStore.class);
        lessorRepository = mock(LessorRepository.class);
        rnRepository = mock(RnRepository.class);
        zamjenska = mock(KpZamjenskaDostava.class);
        when(store.register(any(), any(), any())).thenAnswer(inv -> KpOtpremaEntity.pending(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib(OIB);
        submissionId = UUID.randomUUID();
        SubmissionEntity submission = mock(SubmissionEntity.class);
        when(submission.getEgopKlasa()).thenReturn(KLASA);
        when(submission.getLessorId()).thenReturn(UUID.randomUUID());
        when(filingStore.findSubmission(submissionId)).thenReturn(Optional.of(submission));
    }

    @SuppressWarnings("unchecked")
    private KpOtpremaService service(KpProperties properties, boolean egopEnabled) {
        ObjectProvider<KpClient> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(client);
        return new KpOtpremaService(provider, store, filingStore, lessorRepository, rnRepository,
                new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2)), KpExecutor.direct(),
                zamjenska, new DocumentLabels(), properties, egopEnabled);
    }

    private KpOtpremaService service() {
        return service(KpTestProperties.complete(), true);
    }

    // --- register -------------------------------------------------------------------------

    @Test
    void register_kpDisabled_writesNothing() {
        KpProperties off = new KpProperties(false, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(service(off, true).register(akt(true), lessor, Instant.now()).isEmpty());

        verify(store, never()).register(any(), any(), any());
    }

    /** Bez urudžbe akt nema klasu ni urudžbeni broj — KP bez eGOP-a ne radi. */
    @Test
    void register_egopDisabled_writesNothing() {
        KpOtpremaService service = service(KpTestProperties.complete(), false);

        assertFalse(service.isEnabled());
        assertTrue(service.register(akt(true), lessor, Instant.now()).isEmpty());
        verify(store, never()).register(any(), any(), any());
    }

    @Test
    void register_incomingPismeno_isSkipped() {
        EgopPismenoEntity ulazno = EgopPismenoEntity.create(submissionId, "Zahtjev za registracijski broj",
                EgopPismenoEntity.Smjer.ULAZNO, 1001, "529-06/26-1", "101", false);

        assertTrue(service().register(ulazno, lessor, Instant.now()).isEmpty());

        verify(store, never()).register(any(), any(), any());
    }

    @Test
    void register_naturalPerson_goesToCitizenPretinac() {
        EgopPismenoEntity pismeno = akt(false);
        Instant nastao = Instant.parse("2026-10-21T08:00:00Z");

        assertTrue(service().register(pismeno, lessor, nastao).isPresent());

        verify(store).register(pismeno.getId(), FO, nastao);
    }

    /** Odluka 21. 10. 2026.: pravna osoba prima u poslovni KP tvrtke, ne u pretinac zastupnika. */
    @Test
    void register_legalEntity_goesToBusinessPretinacByJips() {
        // lessorOib je OIB tvrtke; OIB osobe je zastupnik (applyNiasLegalEntity).
        LessorEntity tvrtka = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        tvrtka.setLessorOib(OIB_TVRTKE);
        tvrtka.applyNiasLegalEntity("TESTNA TVRTKA d.o.o.", null, OIB, "Pero Perić", null);
        EgopPismenoEntity pismeno = akt(false);
        Instant nastao = Instant.now();

        service().register(pismeno, tvrtka, nastao);

        verify(store).register(pismeno.getId(), PS, nastao);
    }

    @Test
    void register_withoutOib_writesNothing() {
        lessor.setLessorOib(null);

        assertTrue(service().register(akt(false), lessor, Instant.now()).isEmpty());

        verify(store, never()).register(any(), any(), any());
    }

    /** Pad zapisa otpreme ne smije oboriti tok koji je akt stvorio. */
    @Test
    void register_storeFailure_isSwallowed() {
        doThrow(new IllegalStateException("db down")).when(store).register(any(), any(), any());

        assertTrue(service().register(akt(false), lessor, Instant.now()).isEmpty());
    }

    @Test
    void registerForAkt_loadsLessorAndUsesActCreationTime() {
        EgopPismenoEntity pismeno = akt(false);
        when(lessorRepository.findById(any())).thenReturn(Optional.of(lessor));

        service().registerForAkt(pismeno);

        verify(store).register(pismeno.getId(), FO, pismeno.getCreatedAt());
    }

    // --- slanje ---------------------------------------------------------------------------

    @Test
    void otpremi_ok_sendsActAndMarksSent() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());
        Instant obradeno = Instant.parse("2026-10-21T09:00:00Z");
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of(), "", obradeno, null));

        service().otpremi(pismeno.getId());

        ArgumentCaptor<KpPoruka> poruka = ArgumentCaptor.forClass(KpPoruka.class);
        verify(client).posalji(poruka.capture());
        KpPoruka p = poruka.getValue();
        assertEquals(FO, p.primatelj());
        assertEquals(otprema.getIdPoruke(), p.idPoruke());
        assertEquals(KpTestProperties.TIP_FO, p.tipPoruke());
        assertEquals("Obavijest o suspenziji registracijskog broja", p.naslov());
        assertTrue(p.sadrzaj().contains(RN) && p.sadrzaj().contains("Obavijest o suspenziji"), p.sadrzaj());
        assertEquals(KLASA, p.klasa());
        assertEquals("529-06/26-7", p.urBroj());
        assertEquals("suspenzija-" + RN + ".pdf", p.privitakNaziv());
        assertArrayEquals(PDF, p.privitakPdf());
        assertEquals(pismeno.getId().toString(), p.jedinstveniIdentifikatorPismena());
        verify(store).markSent(otprema.getId(), obradeno, KpTestProperties.TIP_FO, true, null);
    }

    @Test
    void otpremi_businessRecipient_usesPsMessageType() {
        EgopPismenoEntity pismeno = akt(true);
        given(pismeno, PS, Instant.now());
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of(), "", null, null));

        service().otpremi(pismeno.getId());

        ArgumentCaptor<KpPoruka> poruka = ArgumentCaptor.forClass(KpPoruka.class);
        verify(client).posalji(poruka.capture());
        assertEquals(PS, poruka.getValue().primatelj());
        assertEquals(KpTestProperties.TIP_PS, poruka.getValue().tipPoruke());
    }

    @Test
    void otpremi_pismenoNotSynced_waits() {
        EgopPismenoEntity pismeno = akt(false);
        given(pismeno, FO, Instant.now());

        service().otpremi(pismeno.getId());

        verify(client, never()).posalji(any());
        verify(store, never()).markFailed(any(), any(), any(), any(), any());
    }

    @Test
    void otpremi_staleAct_isSkippedNotSent() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now().minus(Duration.ofDays(8)));

        service().otpremi(pismeno.getId());

        verify(client, never()).posalji(any());
        verify(store).markSkipped(eq(otprema.getId()), anyString());
    }

    @Test
    void otpremi_alreadyDone_isNotSentAgain() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = KpOtpremaEntity.pending(pismeno.getId(), FO, Instant.now());
        otprema.markNemaPretinca("K006", "nema KP", null);
        when(store.findByPismeno(pismeno.getId())).thenReturn(Optional.of(otprema));

        service().otpremi(pismeno.getId());

        verify(client, never()).posalji(any());
    }

    /** Isti IdPoruke je već zaprimljen — prijašnji pokušaj je prošao, ali se ishod izgubio. */
    @Test
    void otpremi_duplicate_isRecordedAsSent() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of("K009"), "K009 već postoji", null, null));

        service().otpremi(pismeno.getId());

        verify(store).markSent(eq(otprema.getId()), any(), eq(KpTestProperties.TIP_FO), eq(true), any());
        verify(store, never()).markFailed(any(), any(), any(), any(), any());
    }

    /** Odluka 21. 10. 2026.: primatelj bez aktivnog KP-a dobiva akt e-poštom. */
    @Test
    void otpremi_noActivePretinac_marksNemaPretincaAndDeliversByEmail() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, PS, Instant.now());
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of("Z005"), "Z005 nema KP", null, null));

        service().otpremi(pismeno.getId());

        verify(store).markNemaPretinca(otprema.getId(), "Z005", "Z005 nema KP", null);
        verify(zamjenska).dostavi(otprema.getId());
        verify(store, never()).markFailed(any(), any(), any(), any(), any());
    }

    @Test
    void otpremi_transientCode_marksFailedWithBackoff() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of("M008"), "M008 nedostupno", null, null));
        when(store.markFailed(any(), any(), any(), any(), any())).thenReturn(1);
        Instant before = Instant.now();

        service().otpremi(pismeno.getId());

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(store).markFailed(eq(otprema.getId()), eq("M008"), eq("M008 nedostupno"), next.capture(), any());
        assertTrue(next.getValue().isAfter(before));
        verify(zamjenska, never()).dostavi(any());
    }

    @Test
    void otpremi_transportError_marksFailedAndDoesNotThrow() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());
        when(client.posalji(any())).thenThrow(new KpTransportException("connect timed out"));

        service().otpremi(pismeno.getId());

        verify(store).markFailed(eq(otprema.getId()), eq("TRANSPORT"), eq("connect timed out"), any(), any());
    }

    /** Konfiguracija ili registracija: ponavljanje ne pomaže, pa se ne troše pokušaji. */
    @Test
    void otpremi_permanentCode_isExhaustedAtOnce() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of("K013"), "K013 nije registriran", null, null));

        service().otpremi(pismeno.getId());

        verify(store).markExhausted(otprema.getId(), "K013", "K013 nije registriran", 10, null);
        verify(store, never()).markFailed(any(), any(), any(), any(), any());
    }

    /** Idempotentnost: svaki pokušaj nosi isti IdPoruke. */
    @Test
    void retry_sendsSameIdPoruke() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of("M010"), "M010", null, null));

        KpOtpremaService service = service();
        service.otpremi(pismeno.getId());
        service.retry(otprema.getId());

        ArgumentCaptor<KpPoruka> poruke = ArgumentCaptor.forClass(KpPoruka.class);
        verify(client, times(2)).posalji(poruke.capture());
        assertEquals(otprema.getIdPoruke(), poruke.getAllValues().get(0).idPoruke());
        assertEquals(otprema.getIdPoruke(), poruke.getAllValues().get(1).idPoruke());
    }

    @Test
    void otpremi_pismenoWithoutPdf_isExhaustedWithoutCall() {
        EgopPismenoEntity pismeno = EgopPismenoEntity.forAct(submissionId, RN, UUID.randomUUID().toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, null);
        pismeno.applyPismeno(1002, "529-06/26-7", "102", false);
        pismeno.markDocumentAttached();
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());

        service().otpremi(pismeno.getId());

        verify(client, never()).posalji(any());
        verify(store).markExhausted(eq(otprema.getId()), eq("STR"), anyString(), anyInt(), any());
    }

    /** PS shema ograničava privitak na 1,5 MB Base64 — manje od ukupnih 3 MB. */
    @Test
    void otpremi_pdfTooLargeForBusinessPretinac_isExhausted() {
        EgopPismenoEntity pismeno = akt(true, new byte[1_200_000]);
        KpOtpremaEntity otprema = given(pismeno, PS, Instant.now());

        service().otpremi(pismeno.getId());

        verify(client, never()).posalji(any());
        verify(store).markExhausted(eq(otprema.getId()), eq("STR"), anyString(), anyInt(), any());
    }

    @Test
    void otpremi_samePdfSizeFitsCitizenPretinac() {
        EgopPismenoEntity pismeno = akt(true, new byte[1_200_000]);
        given(pismeno, FO, Instant.now());
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of(), "", null, null));

        service().otpremi(pismeno.getId());

        verify(client).posalji(any());
    }

    /** GsbId odgovora ide uz ishod — po njemu helpdesk APIS-a traži incident. */
    @Test
    void otpremi_recordsGsbIdOfResponse() {
        EgopPismenoEntity pismeno = akt(true);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of("M010"), "M010 [guid g-1]", null, "gsb-1"));

        service().otpremi(pismeno.getId());

        verify(store).markFailed(eq(otprema.getId()), eq("M010"), eq("M010 [guid g-1]"), any(), eq("gsb-1"));
    }

    /**
     * Ograničenje od 3 MB vrijedi za cijeli zahtjev. Uz BASE64 pakiranje poslovna poruka, s
     * privitkom koji je već Base64, kodira se još jednom — isti PDF ondje ne stane.
     */
    @Test
    void sizeLimit_dependsOnDataEncoding() {
        KpOtpremaService embedded = service(KpTestProperties.complete(), true);
        KpOtpremaService base64 = service(KpTestProperties.complete("BASE64", true, false, true, false, 8, false,
                false, 11), true);

        assertTrue(embedded.velicinaZahtjeva(2_000_000) <= KpOtpremaService.MAX_PORUKA_BYTES);
        assertTrue(base64.velicinaZahtjeva(2_000_000) > KpOtpremaService.MAX_PORUKA_BYTES);
        assertTrue(base64.velicinaZahtjeva(1_500_000) <= KpOtpremaService.MAX_PORUKA_BYTES);
    }

    @Test
    void otpremi_pdfTooLargeForBase64Packing_isExhausted() {
        EgopPismenoEntity pismeno = akt(true, new byte[2_000_000]);
        KpOtpremaEntity otprema = given(pismeno, FO, Instant.now());

        service(KpTestProperties.complete("BASE64", true, false, true, false, 8, false, false, 11), true)
                .otpremi(pismeno.getId());

        verify(client, never()).posalji(any());
        verify(store).markExhausted(eq(otprema.getId()), eq("STR"), anyString(), anyInt(), any());
    }

    /** Registracijsko pismeno nema RB na sebi — čita se sa submissiona. */
    @Test
    void otpremi_registrationPismeno_takesRnFromSubmission() {
        EgopPismenoEntity pismeno = EgopPismenoEntity.create(submissionId,
                "Obavijest o dodjeli registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2", "102", false);
        pismeno.keepPdf(PDF);
        pismeno.markDocumentAttached();
        given(pismeno, FO, Instant.now());
        RnEntity rn = mock(RnEntity.class);
        when(rn.getRn()).thenReturn(RN);
        when(rnRepository.findBySubmissionId(submissionId)).thenReturn(List.of(rn));
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of(), "", null, null));

        service().otpremi(pismeno.getId());

        ArgumentCaptor<KpPoruka> poruka = ArgumentCaptor.forClass(KpPoruka.class);
        verify(client).posalji(poruka.capture());
        assertEquals("dodjela-" + RN + ".pdf", poruka.getValue().privitakNaziv());
        assertNull(pismeno.getRn());
    }

    /** Poruka je otišla; pad upisa uspjeha ne smije postati neuspjeh slanja. */
    @Test
    void otpremi_markSentFails_isNotRecordedAsFailure() {
        EgopPismenoEntity pismeno = akt(true);
        given(pismeno, FO, Instant.now());
        when(client.posalji(any())).thenReturn(new KpOdgovor(List.of(), "", null, null));
        doThrow(new IllegalStateException("db down")).when(store).markSent(any(), any(), anyInt(), anyBoolean(), any());

        service().otpremi(pismeno.getId());

        verify(store, never()).markFailed(any(), any(), any(), any(), any());
    }

    @Test
    void otpremi_disabled_doesNothing() {
        service(KpTestProperties.complete(), false).otpremi(UUID.randomUUID());

        verify(store, never()).findByPismeno(any());
    }

    // --- pomoćno --------------------------------------------------------------------------

    private KpOtpremaEntity given(EgopPismenoEntity pismeno, KpPrimatelj primatelj, Instant aktNastao) {
        KpOtpremaEntity otprema = KpOtpremaEntity.pending(pismeno.getId(), primatelj, aktNastao);
        when(store.findByPismeno(pismeno.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        return otprema;
    }

    private EgopPismenoEntity akt(boolean synced) {
        return akt(synced, PDF);
    }

    private EgopPismenoEntity akt(boolean synced, byte[] pdf) {
        EgopPismenoEntity pismeno = EgopPismenoEntity.forAct(submissionId, RN, UUID.randomUUID().toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, pdf);
        if (synced) {
            pismeno.applyPismeno(1002, "529-06/26-7", "102", false);
            pismeno.markDocumentAttached();
        }
        return pismeno;
    }
}
