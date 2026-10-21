package com.str.backend.egop;

import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopTransportException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgopKpOtpremaServiceTest {

    private static final String OIB = "12345678903";
    private static final String USER = "MINT\\str-svc";
    private static final Duration MAX_AKT_AGE = Duration.ofDays(7);

    private EgopClient egopClient;
    private EgopKpOtpremaStore store;
    private EgopFilingStore filingStore;
    private LessorRepository lessorRepository;
    private RnRepository rnRepository;
    private LessorEntity lessor;

    @BeforeEach
    void setUp() {
        egopClient = mock(EgopClient.class);
        store = mock(EgopKpOtpremaStore.class);
        filingStore = mock(EgopFilingStore.class);
        lessorRepository = mock(LessorRepository.class);
        rnRepository = mock(RnRepository.class);
        when(store.register(any(), anyString(), any())).thenAnswer(inv -> EgopKpOtpremaEntity.pending(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib(OIB);
    }

    private EgopKpOtpremaService service(boolean kpEnabled, boolean egopEnabled) {
        return new EgopKpOtpremaService(egopClient, store, filingStore, lessorRepository, rnRepository,
                new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2)), EgopKpExecutor.direct(),
                korisnik(),
                MAX_AKT_AGE, kpEnabled, egopEnabled);
    }

    private EgopKpOtpremaService service() {
        return service(true, true);
    }

    /** Korisnik pod kojim je pismeno urudžbirano — u ovim testovima uvijek aplikacijski. */
    static EgopAktKorisnik korisnik() {
        EgopAktKorisnik korisnik = mock(EgopAktKorisnik.class);
        when(korisnik.za(any())).thenReturn(USER);
        return korisnik;
    }

    // --- register -------------------------------------------------------------------------

    @Test
    void register_kpDisabled_writesNothing() {
        assertTrue(service(false, true).register(izlazno(true), lessor, Instant.now()).isEmpty());

        verify(store, never()).register(any(), anyString(), any());
    }

    /** S mockom bi otprema postala SENT iako ništa nije poslano — pa se uz ugašen eGOP ne radi. */
    @Test
    void register_egopDisabled_writesNothing() {
        EgopKpOtpremaService service = service(true, false);

        assertFalse(service.isEnabled());
        assertTrue(service.register(izlazno(true), lessor, Instant.now()).isEmpty());
        verify(store, never()).register(any(), anyString(), any());
    }

    @Test
    void register_incomingPismeno_isSkipped() {
        EgopPismenoEntity ulazno = EgopPismenoEntity.create(UUID.randomUUID(), "Zahtjev za registracijski broj",
                EgopPismenoEntity.Smjer.ULAZNO, 1001, "529-06/26-1", "101", false);

        assertTrue(service().register(ulazno, lessor, Instant.now()).isEmpty());

        verify(store, never()).register(any(), anyString(), any());
    }

    @Test
    void register_outgoingPismeno_recordsRecipientAndCreationTime() {
        EgopPismenoEntity pismeno = izlazno(false);
        Instant nastao = Instant.parse("2026-10-19T08:00:00Z");

        assertTrue(service().register(pismeno, lessor, nastao).isPresent());

        verify(store).register(pismeno.getId(), OIB, nastao);
    }

    /** Pad zapisa otpreme ne smije oboriti tok koji je akt stvorio. */
    @Test
    void register_storeFailure_isSwallowed() {
        when(store.register(any(), anyString(), any())).thenThrow(new IllegalStateException("db down"));

        assertTrue(service().register(izlazno(false), lessor, Instant.now()).isEmpty());
    }

    /** Akt životnog ciklusa: iznajmljivač se čita preko submissiona, zastarjelost od nastanka akta. */
    @Test
    void registerForAkt_loadsLessorAndUsesActCreationTime() {
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), "HR1", "log-1",
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "pdf".getBytes());
        SubmissionEntity submission = SubmissionEntity.create(null, lessor.getLessorId(), null, null, null, null);
        when(filingStore.findSubmission(akt.getSubmissionId())).thenReturn(Optional.of(submission));
        when(lessorRepository.findById(lessor.getLessorId())).thenReturn(Optional.of(lessor));

        service().registerForAkt(akt);

        verify(store).register(akt.getId(), OIB, akt.getCreatedAt());
    }

    @Test
    void registerForAkt_missingLessor_writesNothing() {
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), "HR1", "log-1",
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "pdf".getBytes());
        when(filingStore.findSubmission(any())).thenReturn(Optional.empty());

        service().registerForAkt(akt);

        verify(store, never()).register(any(), anyString(), any());
    }

    // --- otpremi / retry ------------------------------------------------------------------

    @Test
    void otpremi_syncedPismeno_sendsAndMarksSent() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        given(otprema, pismeno);

        service().otpremi(pismeno.getId());

        verify(egopClient).otpremiUKorisnickiPretinac(eq(USER), eq(1002), eq(OIB), anyString());
        verify(store).markSent(eq(otprema.getId()), any());
        // prvi pokušaj: nema razloga pitati eGOP je li poruka već poslana
        verify(egopClient, never()).dohvatiKpStatusPoruke(anyString(), anyInt(), anyString());
    }

    /** Urudžba nije dovršena (nema dokumenta) — eGOP nema što otpremiti, čeka se. */
    @Test
    void otpremi_pismenoNotSynced_waits() throws Exception {
        EgopPismenoEntity pismeno = izlazno(false);
        given(otprema(pismeno, Instant.now()), pismeno);

        service().otpremi(pismeno.getId());

        verify(egopClient, never()).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());
        verify(store, never()).markFailed(any(), anyString(), any());
    }

    @Test
    void otpremi_alreadySent_isSkipped() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        otprema.markSent(Instant.now());
        when(store.findByPismeno(pismeno.getId())).thenReturn(Optional.of(otprema));

        service().otpremi(pismeno.getId());

        verify(egopClient, never()).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());
    }

    /** Akt koji je predugo čekao urudžbu ne ide u KP — ne smije stranci otvoriti rok. */
    @Test
    void otpremi_staleAct_isSkippedNotSent() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now().minus(MAX_AKT_AGE).minusSeconds(60));
        given(otprema, pismeno);

        service().otpremi(pismeno.getId());

        verify(store).markSkipped(eq(otprema.getId()), anyString());
        verify(egopClient, never()).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void otpremi_skippedOtprema_isNotRetried() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        otprema.markSkipped("zastario");
        when(store.findByPismeno(pismeno.getId())).thenReturn(Optional.of(otprema));

        service().otpremi(pismeno.getId());

        verify(egopClient, never()).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void otpremi_egopError_marksFailedWithBackoffAndDoesNotThrow() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        given(otprema, pismeno);
        doThrow(new EgopBadRequestException("primatelj nema KP"))
                .when(egopClient).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());

        service().otpremi(pismeno.getId());

        verify(store).markFailed(eq(otprema.getId()), eq("primatelj nema KP"), any());
        verify(store, never()).markSent(any(), any());
    }

    /** Poruka je otišla, a upis uspjeha je pao: to nije neuspjeh slanja, inače bi retry poslao drugu. */
    @Test
    void otpremi_markSentFails_isNotRecordedAsSendFailure() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        given(otprema, pismeno);
        doThrow(new IllegalStateException("db down")).when(store).markSent(any(), any());

        service().otpremi(pismeno.getId());

        verify(egopClient).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());
        verify(store, never()).markFailed(any(), any(), any());
    }

    /** Retry prvo pita eGOP: ako je poruka već u pretincu, samo se upiše — nema druge poruke. */
    @Test
    void retry_messageAlreadyInPretinac_marksSentWithoutResending() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        Instant poslano = Instant.parse("2026-10-19T08:01:00Z");
        when(egopClient.dohvatiKpStatusPoruke(USER, 1002, OIB))
                .thenReturn(new KpStatusPoruke(0, poslano, poslano, null, null, null, null, null));

        service().retry(otprema.getId());

        verify(store).markSent(otprema.getId(), poslano);
        verify(egopClient, never()).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());
    }

    /** eGOP poruku ne zna (greška ili prazno vrijeme slanja) — tada se šalje. */
    /**
     * Otpremnica postoji, a eGOP je još nije proslijedio u KP: raniji poziv je prošao, pa se ne
     * šalje druga otpremnica iste poruke.
     */
    @Test
    void retry_otpremnicaCreatedButNotYetSent_isNotSentAgain() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        Instant kreirano = Instant.parse("2026-10-19T08:00:30Z");
        when(egopClient.dohvatiKpStatusPoruke(USER, 1002, OIB))
                .thenReturn(new KpStatusPoruke(0, kreirano, null, null, null, null, null, null));

        service().retry(otprema.getId());

        verify(store).markSent(otprema.getId(), kreirano);
        verify(egopClient, never()).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());
    }

    /** Akt službenika se otprema pod njegovim korisnikom — onim pod kojim je urudžbiran (21.10.2026.). */
    @Test
    void otpremi_sendsUnderUserWhoFiledThePismeno() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        when(store.findByPismeno(pismeno.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        EgopAktKorisnik sluzbenik = mock(EgopAktKorisnik.class);
        when(sluzbenik.za(pismeno)).thenReturn("ana.sluzbenica");
        EgopKpOtpremaService service = new EgopKpOtpremaService(egopClient, store, filingStore, lessorRepository,
                rnRepository, new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2)),
                EgopKpExecutor.direct(), sluzbenik, MAX_AKT_AGE, true, true);

        service.otpremi(pismeno.getId());

        verify(egopClient).otpremiUKorisnickiPretinac(eq("ana.sluzbenica"), eq(1002), eq(OIB), anyString());
    }

    @Test
    void retry_messageUnknownToEgop_sends() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        when(egopClient.dohvatiKpStatusPoruke(anyString(), anyInt(), anyString()))
                .thenThrow(new EgopBadRequestException("poruka ne postoji"));

        service().retry(otprema.getId());

        verify(egopClient).otpremiUKorisnickiPretinac(eq(USER), eq(1002), eq(OIB), anyString());
    }

    @Test
    void retry_transportError_marksFailed() throws Exception {
        EgopPismenoEntity pismeno = izlazno(true);
        EgopKpOtpremaEntity otprema = otprema(pismeno, Instant.now());
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        when(egopClient.dohvatiKpStatusPoruke(anyString(), anyInt(), anyString()))
                .thenReturn(new KpStatusPoruke(0, null, null, null, null, null, null, null));
        doThrow(new EgopTransportException("timeout", new RuntimeException()))
                .when(egopClient).otpremiUKorisnickiPretinac(anyString(), anyInt(), anyString(), anyString());

        service().retry(otprema.getId());

        verify(store).markFailed(eq(otprema.getId()), eq("timeout"), any());
    }

    @Test
    void otpremi_disabled_doesNothing() {
        service(false, true).otpremi(UUID.randomUUID());

        verify(store, never()).findByPismeno(any());
    }

    /** Registracijska pismena nemaju RB na sebi — napomena ga čita sa submissiona. */
    @Test
    void napomena_registrationPismeno_takesRnFromSubmission() {
        EgopPismenoEntity pismeno = izlazno(true);
        RnEntity rn = mock(RnEntity.class);
        when(rn.getRn()).thenReturn("HR123456789012345678");
        when(rnRepository.findBySubmissionId(pismeno.getSubmissionId())).thenReturn(List.of(rn));

        assertEquals("Obavijest o dodjeli registracijskog broja — HR123456789012345678",
                service().napomena(pismeno));
    }

    private void given(EgopKpOtpremaEntity otprema, EgopPismenoEntity pismeno) {
        when(store.findByPismeno(pismeno.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
    }

    private static EgopKpOtpremaEntity otprema(EgopPismenoEntity pismeno, Instant aktNastao) {
        return EgopKpOtpremaEntity.pending(pismeno.getId(), OIB, aktNastao);
    }

    /** Obavijest o dodjeli, urudžbirana; {@code synced} = priložen dokument. */
    private static EgopPismenoEntity izlazno(boolean synced) {
        EgopPismenoEntity pismeno = EgopPismenoEntity.create(UUID.randomUUID(),
                "Obavijest o dodjeli registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2", "102", false);
        if (synced) {
            pismeno.markDocumentAttached();
        }
        return pismeno;
    }
}
