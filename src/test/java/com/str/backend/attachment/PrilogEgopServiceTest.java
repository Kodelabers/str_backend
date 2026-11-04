package com.str.backend.attachment;

import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopAktKorisnik;
import com.str.backend.egop.EgopClient;
import com.str.backend.egop.EgopFilingStore;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopRetryPolicy;
import com.str.backend.egop.exception.EgopTransportException;
import com.str.backend.rn.RnRepository;
import hr.infodom.egov.pismeno.DokumentInfo;
import hr.infodom.egov.pismeno.KreirajPrilogIPridruziDokument;
import hr.infodom.egov.pismeno.PriloziPismenaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrilogEgopServiceTest {

    private static final String SLUZBENIK = "INTERNAL:12345678903";
    private static final String STRANKA = "NIAS:98765432106";

    private PrilogRepository repository;
    private EgopClient egopClient;
    private EgopFilingStore filingStore;
    private EgopAktKorisnik korisnik;
    private final EgopRetryPolicy retryPolicy = new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2));
    private PrilogEgopService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(PrilogRepository.class);
        egopClient = mock(EgopClient.class);
        filingStore = mock(EgopFilingStore.class);
        korisnik = mock(EgopAktKorisnik.class);
        when(egopClient.getVrstePriloga()).thenReturn(Map.of("Prilog", 7));
        when(egopClient.kreirajPrilogIPridruziDokument(any())).thenReturn(new DokumentInfo());
        when(repository.findSadrzaj(anyLong())).thenAnswer(inv -> ("sadrzaj-" + inv.getArgument(0)).getBytes());
        service = service("", "Prilog");
    }

    private PrilogEgopService service(String sifra, String naziv) {
        return new PrilogEgopService(repository, egopClient, retryPolicy, filingStore, korisnik,
                mock(RnRepository.class), sifra, naziv, Duration.ofMinutes(5), 50, true);
    }

    @Test
    void act_attachesEachOfficialAttachmentOnce_withSequenceAndType() throws Exception {
        UUID logId = UUID.randomUUID();
        EgopPismenoEntity akt = akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003);
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "zapisnik.pdf", SLUZBENIK),
                new TestPrilog(12L, VrstaVlasnika.RN_LOG, logId, "foto.jpg", "image/jpeg", 5, SLUZBENIK, null, null)));

        service.prilozi(akt, "ana.sluzbenica");

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient, times(2)).kreirajPrilogIPridruziDokument(sent.capture());
        KreirajPrilogIPridruziDokument prvi = sent.getAllValues().get(0);
        assertThat(prvi.getJop()).isEqualTo(1003);
        assertThat(prvi.getRbrPriloga()).isEqualTo(1);
        assertThat(prvi.getIdVrstaPriloga()).isEqualTo(7);
        assertThat(prvi.getUserName()).isEqualTo("ana.sluzbenica");
        assertThat(prvi.getNazivPriloga()).isEqualTo("zapisnik.pdf");
        assertThat(prvi.getExtension()).isEqualTo("pdf");
        assertThat(prvi.getAttachment()).isEqualTo("sadrzaj-11".getBytes());
        assertThat(sent.getAllValues().get(1).getRbrPriloga()).isEqualTo(2);
        assertThat(sent.getAllValues().get(1).getExtension()).isEqualTo("jpg");
        verify(repository).markEgopPrilozeno(eq(11L), eq(1003), eq(1), any());
        verify(repository).markEgopPrilozeno(eq(12L), eq(1003), eq(2), any());
        verify(repository, never()).markEgopNeuspjeh(anyLong(), any(), any());
    }

    /**
     * Retry: prilog koji je već priložen uz ovo pismeno se preskače, a ostali dobivaju isti redni
     * broj kao u prvom pokušaju.
     */
    @Test
    void retry_skipsAlreadyAttached_keepsSequence() throws Exception {
        UUID logId = UUID.randomUUID();
        EgopPismenoEntity akt = akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003);
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK).prilozen(1003, 1),
                TestPrilog.vezan(12, VrstaVlasnika.RN_LOG, logId, "b.pdf", SLUZBENIK)));

        service.prilozi(akt, "ana.sluzbenica");

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient, times(1)).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getValue().getRbrPriloga()).isEqualTo(2);
        verify(repository, never()).findSadrzaj(11L);
        verify(repository, never()).markEgopPrilozeno(eq(11L), anyInt(), anyInt(), any());
    }

    /**
     * Pad usred popisa ne baca (akt ide dalje): priloženi ostaju označeni, pali dobiva pokušaj, backoff
     * i grešku na svom retku, a sljedeći se i dalje šalje.
     */
    @Test
    void failureMidway_recordsFailureOnThatAttachment_andContinues() throws Exception {
        UUID logId = UUID.randomUUID();
        EgopPismenoEntity akt = akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003);
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK),
                TestPrilog.vezan(12, VrstaVlasnika.RN_LOG, logId, "b.pdf", SLUZBENIK),
                TestPrilog.vezan(13, VrstaVlasnika.RN_LOG, logId, "c.pdf", SLUZBENIK)));
        when(egopClient.kreirajPrilogIPridruziDokument(any()))
                .thenReturn(new DokumentInfo())
                .thenThrow(new EgopTransportException("timeout", null))
                .thenReturn(new DokumentInfo());
        Instant prije = Instant.now();

        assertThatCode(() -> service.prilozi(akt, "ana.sluzbenica")).doesNotThrowAnyException();

        verify(repository).markEgopPrilozeno(eq(11L), eq(1003), eq(1), any());
        ArgumentCaptor<Instant> sljedeci = ArgumentCaptor.forClass(Instant.class);
        verify(repository).markEgopNeuspjeh(eq(12L), contains("timeout"), sljedeci.capture());
        assertThat(sljedeci.getValue()).isAfterOrEqualTo(prije.plus(Duration.ofMinutes(2)));
        verify(repository, never()).markEgopPrilozeno(eq(12L), anyInt(), anyInt(), any());
        verify(repository).markEgopPrilozeno(eq(13L), eq(1003), eq(3), any());
    }

    /** Iscrpljen prilog i prilog kojem backoff još teče se ne šalju; dospjeli neuspjeli se šalje. */
    @Test
    void exhaustedOrNotDue_isSkipped_dueIsRetried() throws Exception {
        UUID logId = UUID.randomUUID();
        EgopPismenoEntity akt = akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003);
        Instant sada = Instant.now();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "iscrpljen.pdf", SLUZBENIK)
                        .neuspio(10, sada.minusSeconds(60)),
                TestPrilog.vezan(12, VrstaVlasnika.RN_LOG, logId, "ceka.pdf", SLUZBENIK)
                        .neuspio(1, sada.plus(Duration.ofHours(1))),
                TestPrilog.vezan(13, VrstaVlasnika.RN_LOG, logId, "dospio.pdf", SLUZBENIK)
                        .neuspio(2, sada.minusSeconds(60))));

        service.prilozi(akt, "x");

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getValue().getNazivPriloga()).isEqualTo("dospio.pdf");
        assertThat(sent.getValue().getRbrPriloga()).isEqualTo(3);
    }

    /** Zadnji dopušteni pokušaj koji padne iscrpljuje prilog — bilježi se kao i svaki neuspjeh (i ERROR u logu). */
    @Test
    void lastAttemptFails_isRecorded() throws Exception {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK).neuspio(9, null)));
        when(egopClient.kreirajPrilogIPridruziDokument(any())).thenThrow(new EgopTransportException("413", null));

        service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x");

        verify(repository).markEgopNeuspjeh(eq(11L), contains("413"), any());
    }

    /**
     * H2: eGOP već ima prilog s našim rbrPriloga (raniji poziv je prošao, upis oznake se izgubio) —
     * preuzima se, ne šalje drugi put. Ostali se šalju.
     */
    @Test
    void existingAttachmentInEgop_isAdopted_notResent() throws Exception {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK),
                TestPrilog.vezan(12, VrstaVlasnika.RN_LOG, logId, "b.pdf", SLUZBENIK)));
        when(egopClient.dohvatiListuPrilogaPismena(any())).thenReturn(List.of(egopPrilog(1003, 1, "a.pdf", true)));

        service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x");

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getValue().getNazivPriloga()).isEqualTo("b.pdf");
        verify(repository).markEgopPrilozeno(eq(11L), eq(1003), eq(1), any());
        verify(repository, never()).findSadrzaj(11L);
    }

    /** Prilog u eGOP-u bez elektroničkog dokumenta nije dovršen: ne preuzima se ni ne šalje naslijepo. */
    @Test
    void existingAttachmentWithoutDocument_isFailure_notResent() throws Exception {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK)));
        when(egopClient.dohvatiListuPrilogaPismena(any())).thenReturn(List.of(egopPrilog(1003, 1, "a.pdf", false)));

        service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x");

        verify(egopClient, never()).kreirajPrilogIPridruziDokument(any());
        verify(repository, never()).markEgopPrilozeno(anyLong(), anyInt(), anyInt(), any());
        verify(repository).markEgopNeuspjeh(eq(11L), contains("bez elektroničkog dokumenta"), any());
    }

    /** Bez popisa priloga iz eGOP-a ništa se ne šalje (mogao bi nastati duplikat); svi se bilježe kao neuspjeh. */
    @Test
    void listingFails_nothingSent_allRecordedAsFailure() throws Exception {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK),
                TestPrilog.vezan(12, VrstaVlasnika.RN_LOG, logId, "b.pdf", SLUZBENIK)));
        when(egopClient.dohvatiListuPrilogaPismena(any())).thenThrow(new EgopTransportException("down", null));

        service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x");

        verify(egopClient, never()).kreirajPrilogIPridruziDokument(any());
        verify(repository).markEgopNeuspjeh(eq(11L), contains("down"), any());
        verify(repository).markEgopNeuspjeh(eq(12L), contains("down"), any());
    }

    /** Prilog je otišao, a upis oznake pao: to nije neuspjeh slanja — retry ga preuzima po rbr. */
    @Test
    void markFailsAfterSuccessfulSend_isNotRecordedAsSendFailure() throws Exception {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK)));
        when(repository.markEgopPrilozeno(anyLong(), anyInt(), anyInt(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));

        assertThatCode(() -> service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x"))
                .doesNotThrowAnyException();

        verify(egopClient).kreirajPrilogIPridruziDokument(any());
        verify(repository, never()).markEgopNeuspjeh(anyLong(), any(), any());
    }

    /** Uz isti prijelaz: ulazno pismeno (podnesak stranke) nosi samo njezine priloge, akt samo službenikove. */
    @Test
    void incomingPismeno_getsOnlyPartyAttachments() throws Exception {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "stranka.pdf", STRANKA),
                TestPrilog.vezan(12, VrstaVlasnika.RN_LOG, logId, "sluzbenik.pdf", SLUZBENIK)));

        service.prilozi(akt(logId, EgopPismenoEntity.Smjer.ULAZNO, 2001), "NIAS");

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getValue().getNazivPriloga()).isEqualTo("stranka.pdf");
        assertThat(sent.getValue().getRbrPriloga()).isEqualTo(1);
    }

    @Test
    void registration_zahtjevGetsSubmissionAttachments_dodjelaNone() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.SUBMISSION, submissionId)).thenReturn(List.of(
                TestPrilog.vezan(21, VrstaVlasnika.SUBMISSION, submissionId, "tlocrt.pdf", STRANKA)));
        EgopPismenoEntity zahtjev = EgopPismenoEntity.create(submissionId, StrDocumentType.ZAHTJEV.vrstaPismenaNaziv(),
                EgopPismenoEntity.Smjer.ULAZNO, 1001, "1", "101", false);
        EgopPismenoEntity dodjela = EgopPismenoEntity.create(submissionId, StrDocumentType.DODJELA.vrstaPismenaNaziv(),
                EgopPismenoEntity.Smjer.IZLAZNO, 1002, "2", "102", false);

        service.prilozi(zahtjev, "MINT\\str-svc");
        service.prilozi(dodjela, "MINT\\str-svc");

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getValue().getJop()).isEqualTo(1001);
        verify(repository).findSazeciVlasnika(VrstaVlasnika.SUBMISSION, submissionId);
    }

    /** Bez priloga nema ni poziva ni razrješavanja vrste priloga — okolina bez šifre radi kao prije. */
    @Test
    void noAttachments_noCalls() throws Exception {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(new ArrayList<>());

        service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x");
        service.prilozi(EgopPismenoEntity.forAct(UUID.randomUUID(), "HR1", "ZAHTJEV_PROMJENE_PODATAKA",
                "Zahtjev za promjenu podataka", EgopPismenoEntity.Smjer.ULAZNO, new byte[]{1}), "x");

        verifyNoInteractions(egopClient);
    }

    @Test
    void configuredTypeCode_winsOverCodebook() throws Exception {
        service = service("42", "Prilog");
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK)));

        service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x");

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getValue().getIdVrstaPriloga()).isEqualTo(42);
        verify(egopClient, never()).getVrstePriloga();
    }

    /** Nerazrješiva vrsta: ništa se ne šalje, a prilog nosi grešku koja imenuje property. */
    @Test
    void unresolvableType_recordsFailure_beforeSending() throws Exception {
        service = service("", "Nepostojeća vrsta");
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK)));

        assertThatCode(() -> service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x"))
                .doesNotThrowAnyException();

        verify(egopClient, never()).kreirajPrilogIPridruziDokument(any());
        verify(repository).markEgopNeuspjeh(eq(11L), contains("str.egop.prilog.vrsta-sifra"), any());
    }

    /** Startup provjera nikad ne obara start, i bez eGOP-a ne zove ništa. */
    @Test
    void startupCheck_neverThrows_andIsSkippedWithoutEgop() {
        assertThatCode(() -> service("", "Nepostojeća vrsta").provjeriVrstuPriloga()).doesNotThrowAnyException();
        new PrilogEgopService(repository, egopClient, retryPolicy, filingStore, korisnik, mock(RnRepository.class),
                "", "Prilog", Duration.ofMinutes(5), 50, false).provjeriVrstuPriloga();
        verify(egopClient, times(1)).getVrstePriloga();
    }

    /** Retry job: kandidati iz upita, pod korisnikom pod kojim je pismeno urudžbirano. */
    @Test
    void ponovi_retriesCandidatePismena_underItsUser() throws Exception {
        UUID logId = UUID.randomUUID();
        EgopPismenoEntity akt = akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003);
        when(repository.findPismenaZaPonovnoPrilaganje(eq(10), any(), any(), eq("REGISTRACIJA"), anyString()))
                .thenReturn(List.of(akt.getId()));
        when(filingStore.findAkt(akt.getId())).thenReturn(Optional.of(akt));
        when(korisnik.za(akt)).thenReturn("ana.sluzbenica");
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK)
                        .neuspio(1, Instant.now().minusSeconds(1))));

        service.ponovi(Instant.now());

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getValue().getUserName()).isEqualTo("ana.sluzbenica");
        verify(repository).markEgopPrilozeno(eq(11L), eq(1003), eq(1), any());
    }

    /**
     * Opoziv nakon T15 ima dva pismena istog prijelaza: ulazni „Zahtjev za opoziv" i izlaznu
     * obavijest o opozivu. Prilog iznajmljivača (non-EU i NIAS) ide samo uz zahtjev.
     */
    @Test
    void revocation_partyAttachments_onlyOnZahtjevZaOpoziv() throws Exception {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(31, VrstaVlasnika.RN_LOG, logId, "izjava.pdf", Ucitao.lessor(UUID.randomUUID())),
                TestPrilog.vezan(32, VrstaVlasnika.RN_LOG, logId, "punomoc.pdf", STRANKA)));
        EgopPismenoEntity zahtjev = EgopPismenoEntity.forAct(UUID.randomUUID(), "HR1", logId.toString(),
                StrDocumentType.ZAHTJEV_OPOZIVA.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.ULAZNO,
                new byte[]{1});
        zahtjev.applyPismeno(2001, "529-06/26-1", "101", false);
        EgopPismenoEntity obavijest = EgopPismenoEntity.forAct(UUID.randomUUID(), "HR1", logId.toString(),
                StrDocumentType.OPOZIV.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.IZLAZNO, new byte[]{2});
        obavijest.applyPismeno(2002, "529-06/26-2", "102", false);

        service.prilozi(obavijest, "app");
        verifyNoInteractions(egopClient);

        service.prilozi(zahtjev, "app");
        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient, times(2)).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getAllValues()).extracting(KreirajPrilogIPridruziDokument::getNazivPriloga)
                .containsExactly("izjava.pdf", "punomoc.pdf");
        assertThat(sent.getAllValues()).extracting(KreirajPrilogIPridruziDokument::getJop).containsOnly(2001);
    }

    private static PriloziPismenaInfo egopPrilog(int jop, int rbr, String naziv, boolean dokument) {
        PriloziPismenaInfo info = new PriloziPismenaInfo();
        info.setJop(jop);
        info.setRbrPriloga(rbr);
        info.setNazivPriloga(naziv);
        info.setPostojiEDokument(dokument);
        return info;
    }

    private static EgopPismenoEntity akt(UUID logId, EgopPismenoEntity.Smjer smjer, int jop) {
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), "HR1", logId.toString(),
                "Obavijest o suspenziji registracijskog broja", smjer, new byte[]{1});
        akt.applyPismeno(jop, "529-06/26-3", "103", false);
        return akt;
    }
}
