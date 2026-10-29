package com.str.backend.egop;

import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopSealingException;
import com.str.backend.eseal.EsealException;
import com.str.backend.eseal.EsealHttpClient;
import com.str.backend.eseal.PdfSealer;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.request.SubmissionEntity;
import hr.infodom.egov.pismeno.DokumentInfo;
import hr.infodom.egov.pismeno.KreirajDokumentZaPismeno;
import hr.infodom.egov.pismeno.KreirajIzlaznoPismeno;
import hr.infodom.egov.pismeno.KreirajPismeno2;
import hr.infodom.egov.pismeno.PismenoBasicInfo2;
import hr.infodom.egov.pismeno.PismenoInfo;
import hr.infodom.egov.predmet.KreirajPredmet2;
import hr.infodom.egov.predmet.OdrediRjesavatelja;
import hr.infodom.egov.predmet.PredmetBasicInfo2;
import hr.infodom.egov.predmet.PredmetInfo;
import hr.infodom.egov.subjekt.KreirajSubjekta;
import hr.infodom.egov.subjekt.SubjektInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgopFilingServiceTest {

    private EgopClient egopClient;
    private EgopFilingStore store;
    private EgopKpOtpremaService kpOtprema;
    private PdfSealer sealer;
    private EgopFilingService service;

    private SubmissionEntity submission;
    private LessorEntity lessor;

    @BeforeEach
    void setUp() {
        egopClient = mock(EgopClient.class);
        store = mock(EgopFilingStore.class);
        kpOtprema = mock(EgopKpOtpremaService.class);
        sealer = mock(PdfSealer.class);
        when(store.readSubjektOznaka(any())).thenReturn(Optional.empty());
        when(store.storeSubjektOznaka(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(store.findPismeno(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(store.savePismeno(any())).thenAnswer(inv -> inv.getArgument(0));

        when(egopClient.getVrstePoslovnihSubjekata()).thenReturn(Map.of("Fizička osoba", "3", "Pravna osoba", "2"));
        when(egopClient.getVrstePredmeta()).thenReturn(Map.of("Izdavanje Registracijskog broja", "9282",
                "Suspenzija, povlačenje i reaktiviranje dodjela registracijskog STR broja", "9283"));
        when(egopClient.getUstroj()).thenReturn(Map.of("MINISTARSTVO TURIZMA", 559));
        // Nazivi kako ih vodi eGOP šifrarnik (MINT, 22.10.2026.) — zahtjev je ondje „…za dodjelu…".
        when(egopClient.getVrstePismena()).thenReturn(Map.of(
                "Zahtjev za dodjelu registracijskog broja", "101",
                "Obavijest o dodjeli registracijskog broja", "102"));

        service = new EgopFilingService(egopClient, store, resolver(), kpOtprema, sealer, properties("MINT", "str-svc"),
                "", "NP");

        lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", "ana@example.com");
        lessor.setLessorOib("12345678901");
        submission = SubmissionEntity.create(null, lessor.getLessorId(), null, null, null, null);
    }

    /** Bez zadanih šifri — sve se razrješava po našim nazivima, kao i prije resolvera. */
    private EgopVrstaResolver resolver() {
        return new EgopVrstaResolver(egopClient, new EgopVrsteProperties(null,
                new EgopVrsteProperties.Nazivi("Izdavanje Registracijskog broja",
                        "Suspenzija, povlačenje i reaktiviranje dodjela registracijskog STR broja", "MINISTARSTVO TURIZMA", Map.of(), null)));
    }

    private static EgopProperties properties(String appDomain, String appUsername) {
        return new EgopProperties(true, "ntlm-user", "ntlm-pass", appDomain, appUsername,
                null, null, "http://egop",
                new EgopProperties.Endpoint("http://mdm"),
                new EgopProperties.Endpoint("http://pismeno"),
                new EgopProperties.Endpoint("http://predmet"),
                new EgopProperties.Endpoint("http://subjekt"));
    }

    @Test
    void fileRegistration_happyPath_createsSubjektPredmetAndTwoPismena() throws Exception {
        SubjektInfo notFound = new SubjektInfo();
        notFound.setOperationSucceeded(false);
        when(egopClient.dohvatiPodatkeSubjekta(any())).thenReturn(notFound);

        SubjektInfo created = new SubjektInfo();
        created.setOperationSucceeded(true);
        created.setOznakaSubjekta(42);
        when(egopClient.kreirajSubjekta(any())).thenReturn(created);

        PredmetBasicInfo2 predmet = new PredmetBasicInfo2();
        predmet.setOperationSucceeded(true);
        predmet.setUredskaGodina(2026);
        predmet.setRbrPredmeta(55);
        predmet.setKlasifikacijskaOznaka("334-01/26-01/55");
        when(egopClient.kreirajPredmet2(any())).thenReturn(predmet);

        when(egopClient.kreirajPismeno2(any())).thenReturn(pismeno(1001, "529-06/26-1"));
        when(egopClient.kreirajIzlaznoPismeno(any())).thenReturn(izlazno(1002, "529-06/26-2"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());

        AtomicReference<FilingReference> seenZahtjev = new AtomicReference<>();
        AtomicReference<FilingReference> seenDodjela = new AtomicReference<>();
        byte[] pdf = "pdf".getBytes();
        byte[] dodjelaPdf = "dodjela".getBytes();

        EgopFilingService.FilingResult result = service.fileRegistration(submission, lessor,
                new EgopDocumentSupplier() {
                    @Override
                    public byte[] zahtjev(FilingReference filing) {
                        seenZahtjev.set(filing);
                        return pdf;
                    }

                    @Override
                    public byte[] obavijestODodjeli(FilingReference filing) {
                        seenDodjela.set(filing);
                        return dodjelaPdf;
                    }
                });

        assertEquals("KLASA: 334-01/26-01/55, URBROJ: 529-06/26-1", result.filingNumber());
        assertEquals(new FilingReference("334-01/26-01/55", "529-06/26-1", 1001), seenZahtjev.get());
        // Izlazno pismeno ima vlastiti URBROJ — dokument mu se mora graditi na toj oznaci,
        // ne na oznaci zahtjeva (raniji kod je prilagao istu datoteku oba puta).
        assertEquals(new FilingReference("334-01/26-01/55", "529-06/26-2", 1002), seenDodjela.get());
        assertEquals(EgopSyncStatus.SYNCED, submission.getEgopSyncStatus());
        assertEquals(42, lessor.getEgopSubjektOznaka());
        assertEquals("334-01/26-01/55", submission.getEgopKlasa());

        ArgumentCaptor<KreirajSubjekta> subjektCaptor = ArgumentCaptor.forClass(KreirajSubjekta.class);
        verify(egopClient).kreirajSubjekta(subjektCaptor.capture());
        assertEquals("MINT\\str-svc", subjektCaptor.getValue().getUserName());
        assertEquals("3", subjektCaptor.getValue().getTipOsobe());
        assertEquals("Ana Anić", subjektCaptor.getValue().getNaziv());

        ArgumentCaptor<KreirajPredmet2> predmetCaptor = ArgumentCaptor.forClass(KreirajPredmet2.class);
        verify(egopClient).kreirajPredmet2(predmetCaptor.capture());
        assertEquals("NP", predmetCaptor.getValue().getUpisnaKnjiga());
        assertEquals("9282", predmetCaptor.getValue().getVrstaPredmeta());
        assertEquals(559, predmetCaptor.getValue().getNadleznaOrgJedinica());
        assertEquals(42, predmetCaptor.getValue().getSubjektOznaka());

        // Zahtjev je ulazno pismeno (KreirajPismeno2, sa subjektom), obavijest izlazno
        // (KreirajIzlaznoPismeno) — eGOP smjer ne izvodi pouzdano iz vrste.
        ArgumentCaptor<KreirajPismeno2> ulaznoCaptor = ArgumentCaptor.forClass(KreirajPismeno2.class);
        verify(egopClient).kreirajPismeno2(ulaznoCaptor.capture());
        assertEquals("101", ulaznoCaptor.getValue().getVrstaPismena());
        ArgumentCaptor<KreirajIzlaznoPismeno> izlaznoCaptor = ArgumentCaptor.forClass(KreirajIzlaznoPismeno.class);
        verify(egopClient).kreirajIzlaznoPismeno(izlaznoCaptor.capture());
        assertEquals("102", izlaznoCaptor.getValue().getVrstaPismena());
        assertEquals("Obavijest o dodjeli registracijskog broja", izlaznoCaptor.getValue().getNazivPismena());
        // Oba pismena moraju nositi predmet u koji idu. Kad eGOP nije uključen, urudžbeni broj
        // dodjeljuje LocalFilingNumberAllocator brojanjem pismena UNUTAR tog predmeta — regresija
        // na 0/null ovdje tiho svali sve predmete u isti brojač i razbije "podnesak 1, akt 2",
        // a da nijedan drugi test ne padne.
        assertEquals(55, ulaznoCaptor.getValue().getRbrSpisa());
        assertEquals((short) 2026, ulaznoCaptor.getValue().getUredskaGodina());
        assertEquals(55, izlaznoCaptor.getValue().getRbrSpisa());
        assertEquals((short) 2026, izlaznoCaptor.getValue().getUredskaGodina());

        ArgumentCaptor<KreirajDokumentZaPismeno> dokCaptor = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient, times(2)).kreirajDokumentZaPismeno(dokCaptor.capture());
        assertEquals(1001, dokCaptor.getAllValues().get(0).getJop());
        assertEquals(1002, dokCaptor.getAllValues().get(1).getJop());
        assertEquals("pdf", dokCaptor.getAllValues().get(0).getExtension());

        ArgumentCaptor<EgopPismenoEntity> rowCaptor = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(store, times(2)).savePismeno(rowCaptor.capture());
        assertEquals(EgopPismenoEntity.Smjer.ULAZNO, rowCaptor.getAllValues().get(0).getSmjer());
        assertEquals(EgopPismenoEntity.Smjer.IZLAZNO, rowCaptor.getAllValues().get(1).getSmjer());
        verify(store, times(2)).markDocumentAttached(any());

        verify(store).savePredmet(submission.getSubmissionId(), 2026, 55, "334-01/26-01/55");
        verify(store).saveSuccess(eq(submission.getSubmissionId()), eq(result.filingNumber()), eq(pdf));
    }

    /** Bez izričite konfiguracije rješavatelj je servisni račun — InfoDom je predložio
     *  upravo to (26.07.2026), pa vrijednost mora doći do poziva neizmijenjena. */
    @Test
    void fileRegistration_defaultsRjesavateljToAppUsername() throws Exception {
        givenSubjektAndPredmetCreated();

        service.fileRegistration(submission, lessor, documents("pdf".getBytes()));

        ArgumentCaptor<OdrediRjesavatelja> captor = ArgumentCaptor.forClass(OdrediRjesavatelja.class);
        verify(egopClient).odrediRjesavatelja(captor.capture());
        assertEquals("MINT\\str-svc", captor.getValue().getRjesavatelj());
    }

    @Test
    void fileRegistration_usesConfiguredRjesavateljWhenSet() throws Exception {
        service = new EgopFilingService(egopClient, store, resolver(), kpOtprema, sealer, properties("MINT", "str-svc"),
                "MINT\\ivan.ivic", "NP");
        givenSubjektAndPredmetCreated();

        service.fileRegistration(submission, lessor, documents("pdf".getBytes()));

        ArgumentCaptor<OdrediRjesavatelja> captor = ArgumentCaptor.forClass(OdrediRjesavatelja.class);
        verify(egopClient).odrediRjesavatelja(captor.capture());
        assertEquals("MINT\\ivan.ivic", captor.getValue().getRjesavatelj());
        // userName ostaje servisni račun — mijenja se samo rješavatelj
        assertEquals("MINT\\str-svc", captor.getValue().getUserName());
    }

    @Test
    void fileRegistration_resumesFromStoredSubjektAndPredmet() throws Exception {
        lessor.assignEgopSubjekt(42);
        submission.applyEgopPredmet(2026, 55, "334-01/26-01/55");

        PredmetInfo postojeci = new PredmetInfo();
        postojeci.setOperationSucceeded(true);
        when(egopClient.dohvatiPodatkePredmeta(any())).thenReturn(postojeci);

        EgopPismenoEntity postojeceUlazno = EgopPismenoEntity.create(
                submission.getSubmissionId(), "Zahtjev za registracijski broj",
                EgopPismenoEntity.Smjer.ULAZNO, 1001, "529-06/26-1", "101", false);
        postojeceUlazno.markDocumentAttached();
        when(store.findPismeno(submission.getSubmissionId(), "Zahtjev za registracijski broj",
                EgopPismenoEntity.ACT_REF_REGISTRACIJA))
                .thenReturn(Optional.of(postojeceUlazno));

        when(egopClient.kreirajIzlaznoPismeno(any())).thenReturn(izlazno(1002, "529-06/26-2"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());

        EgopFilingService.FilingResult result = service.fileRegistration(submission, lessor, documents("pdf".getBytes()));

        assertEquals("KLASA: 334-01/26-01/55, URBROJ: 529-06/26-1", result.filingNumber());
        assertEquals(EgopSyncStatus.SYNCED, submission.getEgopSyncStatus());
        verify(egopClient, never()).dohvatiPodatkeSubjekta(any());
        verify(egopClient, never()).kreirajSubjekta(any());
        verify(egopClient, never()).kreirajPredmet2(any());
        // samo izlazno pismeno se kreira — ulazno već postoji
        verify(egopClient, never()).kreirajPismeno2(any());
        verify(egopClient, times(1)).kreirajIzlaznoPismeno(any());
        // ulazno već ima priložen dokument — prilaže se samo izlazni, bez duplikata
        verify(egopClient, times(1)).kreirajDokumentZaPismeno(any());
    }

    /** Paralelna registracija istog iznajmljivača upisala je oznaku prije nas —
     *  vrijedi njezina, ne ona koju je vratio naš KreirajSubjekta. */
    @Test
    void ensureSubjekt_losesRace_usesStoredOznaka() throws Exception {
        SubjektInfo notFound = new SubjektInfo();
        notFound.setOperationSucceeded(false);
        when(egopClient.dohvatiPodatkeSubjekta(any())).thenReturn(notFound);

        SubjektInfo created = new SubjektInfo();
        created.setOperationSucceeded(true);
        created.setOznakaSubjekta(42);
        when(egopClient.kreirajSubjekta(any())).thenReturn(created);
        when(store.storeSubjektOznaka(any(), eq(42))).thenReturn(99);

        when(egopClient.getVrstePredmeta()).thenReturn(Map.of());
        assertThrows(EgopBadRequestException.class,
                () -> service.fileRegistration(submission, lessor, documents("pdf".getBytes())));

        assertEquals(99, lessor.getEgopSubjektOznaka());
    }

    @Test
    void fileRegistration_missingCodebookEntry_throwsAndLeavesProgress() throws Exception {
        lessor.assignEgopSubjekt(42);
        when(egopClient.getVrstePredmeta()).thenReturn(Map.of());

        EgopBadRequestException e = assertThrows(EgopBadRequestException.class,
                () -> service.fileRegistration(submission, lessor, documents("pdf".getBytes())));

        assertTrue(e.getMessage().contains("vrsta predmeta"));
        assertEquals(EgopSyncStatus.SUBJEKT_OK, submission.getEgopSyncStatus());
        verify(store).saveSyncStatus(submission.getSubmissionId(), EgopSyncStatus.SUBJEKT_OK);
        verify(egopClient, never()).kreirajPismeno2(any());
    }

    private void givenSubjektAndPredmetCreated() throws Exception {
        lessor.assignEgopSubjekt(42);

        PredmetBasicInfo2 predmet = new PredmetBasicInfo2();
        predmet.setOperationSucceeded(true);
        predmet.setUredskaGodina(2026);
        predmet.setRbrPredmeta(55);
        predmet.setKlasifikacijskaOznaka("334-01/26-01/55");
        when(egopClient.kreirajPredmet2(any())).thenReturn(predmet);

        when(egopClient.kreirajPismeno2(any())).thenReturn(pismeno(1001, "529-06/26-1"));
        when(egopClient.kreirajIzlaznoPismeno(any())).thenReturn(izlazno(1002, "529-06/26-2"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());
    }

    private PismenoBasicInfo2 pismeno(int jop, String urBroj) {
        PismenoBasicInfo2 p = new PismenoBasicInfo2();
        p.setOperationSucceeded(true);
        p.setJop(jop);
        p.setUrBroj(urBroj);
        return p;
    }

    private PismenoInfo izlazno(int jop, String urBroj) {
        PismenoInfo p = new PismenoInfo();
        p.setOperationSucceeded(true);
        p.setJop(jop);
        p.setUrBroj(urBroj);
        p.setUI("I");
        return p;
    }

    /**
     * Pismeno je dovršeno tek kad mu je priložen dokument — status tada mora biti SYNCED,
     * inače red trajno ostaje na PISMENO_OK iako je posao gotov.
     */
    @Test
    void fileRegistration_marksPismenaSyncedAfterAttachingDocument() throws Exception {
        givenSubjektAndPredmetCreated();
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());

        ArgumentCaptor<EgopPismenoEntity> captor = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        service.fileRegistration(submission, lessor, documents("pdf".getBytes()));

        verify(store, atLeastOnce()).savePismeno(captor.capture());
        // Captor drži iste instance koje tok mutira, pa se provjerava konačno stanje.
        assertTrue(captor.getAllValues().stream().allMatch(EgopPismenoEntity::isSynced),
                "nakon prilaganja dokumenta pismeno mora biti SYNCED, ne ostati na PISMENO_OK");
        assertTrue(captor.getAllValues().stream().allMatch(EgopPismenoEntity::isDocumentAttached),
                "dokument mora biti označen kao priložen");
    }

    /**
     * Otprema u KP: samo za izlazno pismeno, zapisana prije prilaganja dokumenta (da je retry nađe
     * i kad tok padne između) i poslana tek na kraju, kad je urudžba gotova.
     */
    @Test
    void fileRegistration_registersAndSendsKpOnlyForOutgoingPismeno() throws Exception {
        givenSubjektAndPredmetCreated();

        service.fileRegistration(submission, lessor, documents("pdf".getBytes()));

        ArgumentCaptor<EgopPismenoEntity> registered = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(kpOtprema, atLeastOnce()).register(registered.capture(), eq(lessor), eq(submission.getCreatedAt()));
        assertTrue(registered.getAllValues().stream()
                        .allMatch(p -> p.getSmjer() == EgopPismenoEntity.Smjer.IZLAZNO),
                "u KP ide samo obavijest o dodjeli, ne zahtjev stranke");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(kpOtprema, store);
        order.verify(kpOtprema).register(any(), eq(lessor), any());
        order.verify(store).saveSuccess(any(), anyString(), any());
        order.verify(kpOtprema).otpremi(registered.getValue().getId());
    }

    /**
     * Akt životnog ciklusa: otpremu je zapisao listener kad je akt nastao, pa je fileAct samo
     * pokreće — novo zapisivanje ovdje bi poslalo i akte nastale dok je KP bio ugašen.
     */
    @Test
    void fileAct_sendsKpAfterFiling_butDoesNotRegister() throws Exception {
        lessor.assignEgopSubjekt(42);
        submission.applyEgopPredmet(2026, 55, "334-01/26-01/55");
        submission.applyEgopAktiPredmet(2026, 77, "334-06/11-26/77");
        PredmetInfo postoji = new PredmetInfo();
        postoji.setOperationSucceeded(true);
        when(egopClient.dohvatiPodatkePredmeta(any())).thenReturn(postoji);
        when(egopClient.getVrstePismena()).thenReturn(Map.of("Obavijest o suspenziji registracijskog broja", "103"));
        when(egopClient.kreirajIzlaznoPismeno(any())).thenReturn(izlazno(1003, "529-06/26-3"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(submission.getSubmissionId(), "HR1", "log-1",
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "pdf".getBytes());

        service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.empty());

        verify(kpOtprema).otpremi(akt.getId());
        verify(kpOtprema, never()).register(any(), any(), any());
    }

    /**
     * Prvi akt nakon dodjele otvara predmet akata (klasa 334-06/11) pod službenikom, koji je i
     * rješavatelj; pismeno ide u taj predmet, a u eGOP se prilaže konačni dokument s klasom i
     * urbrojem umjesto snimka.
     */
    @Test
    void fileAct_firstAct_opensAktiPredmetUnderOfficial_andAttachesFinalDocument() throws Exception {
        lessor.assignEgopSubjekt(42);
        PredmetBasicInfo2 predmet = new PredmetBasicInfo2();
        predmet.setOperationSucceeded(true);
        predmet.setUredskaGodina(2026);
        predmet.setRbrPredmeta(77);
        predmet.setKlasifikacijskaOznaka("334-06/11-26/77");
        when(egopClient.kreirajPredmet2(any())).thenReturn(predmet);
        when(egopClient.getVrstePismena()).thenReturn(Map.of("Obavijest o suspenziji registracijskog broja", "103"));
        when(egopClient.kreirajIzlaznoPismeno(any())).thenReturn(izlazno(1003, "529-06-03/01-27-5-1"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(submission.getSubmissionId(), "HR1", "log-1",
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "snimak".getBytes());
        AtomicReference<FilingReference> seen = new AtomicReference<>();

        service.fileAct(akt, submission, lessor, "ana.sluzbenica", filing -> {
            seen.set(filing);
            return Optional.of("konacni".getBytes());
        });

        ArgumentCaptor<KreirajPredmet2> predmetRequest = ArgumentCaptor.forClass(KreirajPredmet2.class);
        verify(egopClient).kreirajPredmet2(predmetRequest.capture());
        assertEquals("9283", predmetRequest.getValue().getVrstaPredmeta());
        assertEquals("ana.sluzbenica", predmetRequest.getValue().getUserName());
        assertTrue(predmetRequest.getValue().getNazivPredmeta().contains("HR1"));
        verify(store).saveAktiPredmet(submission.getSubmissionId(), 2026, 77, "334-06/11-26/77");

        ArgumentCaptor<OdrediRjesavatelja> rjesavatelj = ArgumentCaptor.forClass(OdrediRjesavatelja.class);
        verify(egopClient).odrediRjesavatelja(rjesavatelj.capture());
        assertEquals("ana.sluzbenica", rjesavatelj.getValue().getRjesavatelj());

        ArgumentCaptor<KreirajIzlaznoPismeno> pismenoRequest = ArgumentCaptor.forClass(KreirajIzlaznoPismeno.class);
        verify(egopClient).kreirajIzlaznoPismeno(pismenoRequest.capture());
        assertEquals("ana.sluzbenica", pismenoRequest.getValue().getUserName());
        assertEquals(77, pismenoRequest.getValue().getRbrSpisa());

        assertEquals(new FilingReference("334-06/11-26/77", "529-06-03/01-27-5-1", 1003), seen.get());
        verify(store).replacePdf(akt.getId(), "konacni".getBytes());
        ArgumentCaptor<KreirajDokumentZaPismeno> dokument = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient).kreirajDokumentZaPismeno(dokument.capture());
        assertEquals("konacni", new String(dokument.getValue().getAttachment()));
        assertEquals("ana.sluzbenica", dokument.getValue().getUserName());
    }

    /**
     * Pismeno kreirano prije predmeta akata živi u predmetu registracije: dovršava se ondje,
     * bez novog predmeta i bez dokumenta s klasom predmeta u kojem pismeno nije.
     */
    @Test
    void fileAct_pismenoFiledBeforeAktiPredmet_isCompletedInRegistrationPredmet() throws Exception {
        lessor.assignEgopSubjekt(42);
        submission.applyEgopPredmet(2026, 55, "334-06/10-26/55");
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(submission.getSubmissionId(), "HR1", "log-3",
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "snimak".getBytes());
        akt.applyPismeno(1005, "529-06/26-3", "103", false);
        AtomicReference<FilingReference> seen = new AtomicReference<>();

        service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> {
            seen.set(filing);
            return Optional.of("konacni".getBytes());
        });

        verify(egopClient, never()).kreirajPredmet2(any());
        verify(egopClient, never()).kreirajIzlaznoPismeno(any());
        assertEquals(null, seen.get(), "konačni dokument se ne renderira");
        ArgumentCaptor<KreirajDokumentZaPismeno> dokument = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient).kreirajDokumentZaPismeno(dokument.capture());
        assertEquals("snimak", new String(dokument.getValue().getAttachment()));
    }

    /**
     * Opoziv je ulazno pismeno (MINT, 22.10.2026.): ide kroz KreirajPismeno2 sa subjektom
     * (iznajmljivač), u predmet akata, nikad kroz KreirajIzlaznoPismeno.
     */
    @Test
    void fileAct_revocation_isFiledAsIncomingWithSubject() throws Exception {
        lessor.assignEgopSubjekt(42);
        submission.applyEgopAktiPredmet(2026, 77, "334-06/11-26/77");
        PredmetInfo postoji = new PredmetInfo();
        postoji.setOperationSucceeded(true);
        when(egopClient.dohvatiPodatkePredmeta(any())).thenReturn(postoji);
        when(egopClient.getVrstePismena()).thenReturn(Map.of("Obavijest o opozivu registracijskog broja", "105"));
        when(egopClient.kreirajPismeno2(any())).thenReturn(pismeno(1006, "529-06/26-6"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(submission.getSubmissionId(), "HR1", "log-6",
                StrDocumentType.OPOZIV.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.ULAZNO, "snimak".getBytes());

        service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.empty());

        ArgumentCaptor<KreirajPismeno2> request = ArgumentCaptor.forClass(KreirajPismeno2.class);
        verify(egopClient).kreirajPismeno2(request.capture());
        assertEquals(77, request.getValue().getRbrSpisa());
        assertEquals("105", request.getValue().getVrstaPismena());
        assertEquals(42, request.getValue().getSubjektOznaka());
        assertEquals("12345678901", request.getValue().getSubjektOIB());
        verify(egopClient, never()).kreirajIzlaznoPismeno(any());
    }

    /** Svi kasniji akti istog RB-a idu u već otvoren predmet akata. */
    @Test
    void fileAct_existingAktiPredmet_isReused() throws Exception {
        lessor.assignEgopSubjekt(42);
        submission.applyEgopAktiPredmet(2026, 77, "334-06/11-26/77");
        PredmetInfo postoji = new PredmetInfo();
        postoji.setOperationSucceeded(true);
        when(egopClient.dohvatiPodatkePredmeta(any())).thenReturn(postoji);
        when(egopClient.getVrstePismena()).thenReturn(Map.of("Obavijest o povlačenju registracijskog broja", "104"));
        when(egopClient.kreirajIzlaznoPismeno(any())).thenReturn(izlazno(1004, "529-06-03/01-27-5-2"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(submission.getSubmissionId(), "HR1", "log-2",
                "Obavijest o povlačenju registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "snimak".getBytes());

        service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.empty());

        verify(egopClient, never()).kreirajPredmet2(any());
        verify(store, never()).replacePdf(any(), any());
        ArgumentCaptor<KreirajDokumentZaPismeno> dokument = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient).kreirajDokumentZaPismeno(dokument.capture());
        assertEquals("snimak", new String(dokument.getValue().getAttachment()), "bez konačnog vrijedi snimak");
    }

    /** Ako urudžba padne, otprema se ne pokušava — njen red ostaje za retry. */
    @Test
    void fileRegistration_failedFiling_doesNotSendKp() throws Exception {
        givenSubjektAndPredmetCreated();
        when(egopClient.kreirajDokumentZaPismeno(any())).thenThrow(new EgopBadRequestException("boom"));

        assertThrows(EgopBadRequestException.class,
                () -> service.fileRegistration(submission, lessor, documents("pdf".getBytes())));

        verify(kpOtprema, never()).otpremi(any());
    }

    /**
     * Render obavijesti padne: izlazno pismeno ostaje bez dokumenta, otprema se ne šalje, a
     * submission se ne označava kao SYNCED — retry nastavlja od tog pismena.
     */
    @Test
    void fileRegistration_noticeRenderFails_leavesOutgoingUnattachedAndUnsent() throws Exception {
        givenSubjektAndPredmetCreated();
        EgopDocumentSupplier documents = new EgopDocumentSupplier() {
            @Override
            public byte[] zahtjev(FilingReference filing) {
                return "zahtjev".getBytes();
            }

            @Override
            public byte[] obavijestODodjeli(FilingReference filing) {
                throw new IllegalStateException("render");
            }
        };

        assertThrows(IllegalStateException.class, () -> service.fileRegistration(submission, lessor, documents));

        // samo ulazno pismeno je dobilo dokument
        verify(egopClient, times(1)).kreirajDokumentZaPismeno(any());
        verify(store, never()).saveSuccess(any(), anyString(), any());
        verify(kpOtprema, never()).otpremi(any());
    }

    /** Isti PDF za oba pismena — testovima koji ne provjeravaju sadržaj dokumenta. */
    private static EgopDocumentSupplier documents(byte[] pdf) {
        return new EgopDocumentSupplier() {
            @Override
            public byte[] zahtjev(FilingReference filing) {
                return pdf;
            }

            @Override
            public byte[] obavijestODodjeli(FilingReference filing) {
                return pdf;
            }
        };
    }

    // --- kvalificirani e-pečat izlaznih akata ---------------------------------------

    private static final byte[] PECACENI = "%PDF-pecaceni".getBytes();
    private static final UUID GUID = UUID.fromString("3f2b8c1e-0d4a-4e57-9a61-2c7d5b9e8f10");

    private void givenSealing() {
        when(sealer.isEnabled()).thenReturn(true);
        when(store.freezeForSealing(any(), any(), anyString())).thenAnswer(
                inv -> new EgopFilingStore.SealRequest("kljuc-0000000001", inv.getArgument(1)));
        when(store.applySeal(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(sealer.seal(any(), anyString(), any())).thenReturn(new EsealHttpClient.Sealed(
                PECACENI, GUID, "ABCD-EFGH-JKMN", "B-LT", "sha-256=:x:", "1.0.0", false));
    }

    private EgopPismenoEntity givenAktSuspenzije() throws Exception {
        lessor.assignEgopSubjekt(42);
        submission.applyEgopPredmet(2026, 55, "334-01/26-01/55");
        submission.applyEgopAktiPredmet(2026, 77, "334-06/11-26/77");
        PredmetInfo postoji = new PredmetInfo();
        postoji.setOperationSucceeded(true);
        when(egopClient.dohvatiPodatkePredmeta(any())).thenReturn(postoji);
        when(egopClient.getVrstePismena()).thenReturn(Map.of("Obavijest o suspenziji registracijskog broja", "103"));
        when(egopClient.kreirajIzlaznoPismeno(any())).thenReturn(izlazno(1003, "529-06/26-3"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());
        return EgopPismenoEntity.forAct(submission.getSubmissionId(), "HR1", "log-1",
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "snimak".getBytes());
    }

    /**
     * Pečatira se konačni dokument (s klasom i urbrojem), pod spremljenim ključem, a u eGOP i KP ide
     * pečaćeni — nepečaćeni se sprema prije poziva da ga retry može poslati nepromijenjenog.
     */
    @Test
    void fileAct_sealingEnabled_attachesSealedDocumentAndSendsKp() throws Exception {
        givenSealing();
        EgopPismenoEntity akt = givenAktSuspenzije();
        byte[] konacni = "konacni".getBytes();

        service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.of(konacni));

        verify(sealer).seal(konacni, "kljuc-0000000001", "529-06/26-3");
        ArgumentCaptor<KreirajDokumentZaPismeno> dok = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient).kreirajDokumentZaPismeno(dok.capture());
        assertArrayEquals(PECACENI, dok.getValue().getAttachment());
        verify(store).applySeal(akt.getId(), PECACENI, GUID, "ABCD-EFGH-JKMN", "B-LT", "sha-256=:x:");
        assertTrue(akt.isSealed());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(store, sealer, egopClient, kpOtprema);
        order.verify(store).replacePdf(akt.getId(), konacni);
        order.verify(store).freezeForSealing(eq(akt.getId()), eq(konacni), anyString());
        order.verify(sealer).seal(any(), anyString(), any());
        order.verify(egopClient).kreirajDokumentZaPismeno(any());
        order.verify(kpOtprema).otpremi(akt.getId());
    }

    /** Bez pečata akt ne smije ni u eGOP ni u KP; razlog ostaje u poruci za sync_error. */
    @Test
    void fileAct_sealingFails_doesNotAttachOrSend() throws Exception {
        givenSealing();
        when(sealer.seal(any(), anyString(), any())).thenThrow(
                new EsealException(422, "PDF_NOT_PDFA", "trace-1", false, null));
        EgopPismenoEntity akt = givenAktSuspenzije();

        EgopSealingException e = assertThrows(EgopSealingException.class,
                () -> service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.empty()));

        assertTrue(e.getMessage().contains("PDF_NOT_PDFA"));
        assertTrue(e.getMessage().contains("trace-1"));
        verify(egopClient, never()).kreirajDokumentZaPismeno(any());
        verify(kpOtprema, never()).otpremi(any());
        assertFalse(akt.isSealed());
        assertFalse(akt.isDocumentAttached());
    }

    /** Retry nakon pečata, a prije prilaganja: nema novog rendera ni drugog pečatiranja. */
    @Test
    void fileAct_alreadySealed_attachesStoredSealWithoutRerender() throws Exception {
        givenSealing();
        EgopPismenoEntity akt = givenAktSuspenzije();
        akt.applyPismeno(1003, "529-06/26-3", "103", false);
        akt.applySeal(PECACENI, GUID, "ABCD-EFGH-JKMN", "B-LT", "sha-256=:x:");

        service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> {
            throw new AssertionError("pečaćeni akt se ne smije renderirati ponovo");
        });

        verify(sealer, never()).seal(any(), anyString(), any());
        ArgumentCaptor<KreirajDokumentZaPismeno> dok = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient).kreirajDokumentZaPismeno(dok.capture());
        assertArrayEquals(PECACENI, dok.getValue().getAttachment());
        verify(kpOtprema).otpremi(akt.getId());
    }

    @Test
    void fileAct_sealingDisabled_attachesDocumentAsBefore() throws Exception {
        EgopPismenoEntity akt = givenAktSuspenzije();

        service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.empty());

        verify(sealer, never()).seal(any(), anyString(), any());
        ArgumentCaptor<KreirajDokumentZaPismeno> dok = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient).kreirajDokumentZaPismeno(dok.capture());
        assertArrayEquals("snimak".getBytes(), dok.getValue().getAttachment());
    }

    /** Pečatira se samo obavijest o dodjeli; zahtjev je podnesak stranke i ide kakav jest. */
    @Test
    void fileRegistration_sealingEnabled_sealsOnlyOutgoingNotice() throws Exception {
        givenSubjektAndPredmetCreated();
        givenSealing();
        byte[] zahtjev = "zahtjev".getBytes();
        byte[] dodjela = "dodjela".getBytes();

        service.fileRegistration(submission, lessor, new EgopDocumentSupplier() {
            @Override
            public byte[] zahtjev(FilingReference filing) {
                return zahtjev;
            }

            @Override
            public byte[] obavijestODodjeli(FilingReference filing) {
                return dodjela;
            }
        });

        verify(sealer, times(1)).seal(dodjela, "kljuc-0000000001", "529-06/26-2");
        ArgumentCaptor<KreirajDokumentZaPismeno> dok = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient, times(2)).kreirajDokumentZaPismeno(dok.capture());
        assertArrayEquals(zahtjev, dok.getAllValues().get(0).getAttachment());
        assertArrayEquals(PECACENI, dok.getAllValues().get(1).getAttachment());
    }

    /**
     * Retry registracije nakon pada pečatiranja šalje spremljenu obavijest, ne novi render — servis
     * bi drugačije bajtove pod istim ključem odbio s IDEMPOTENCY_KEY_REUSED.
     */
    @Test
    void fileRegistration_retryAfterSealFailure_sealsStoredNotice() throws Exception {
        givenSubjektAndPredmetCreated();
        givenSealing();
        EgopPismenoEntity izlazno = EgopPismenoEntity.create(submission.getSubmissionId(),
                StrDocumentType.DODJELA.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2", "102", false);
        byte[] spremljena = "spremljena-dodjela".getBytes();
        izlazno.replacePdf(spremljena);
        ReflectionTestUtils.setField(izlazno, "sealIdempotencyKey", "kljuc-0000000001");
        when(store.findPismeno(submission.getSubmissionId(), StrDocumentType.DODJELA.vrstaPismenaNaziv(),
                EgopPismenoEntity.ACT_REF_REGISTRACIJA)).thenReturn(Optional.of(izlazno));

        service.fileRegistration(submission, lessor, new EgopDocumentSupplier() {
            @Override
            public byte[] zahtjev(FilingReference filing) {
                return "zahtjev".getBytes();
            }

            @Override
            public byte[] obavijestODodjeli(FilingReference filing) {
                throw new AssertionError("spremljena obavijest se ne renderira ponovo");
            }
        });

        verify(sealer).seal(spremljena, "kljuc-0000000001", "529-06/26-2");
        verify(store, never()).freezeForSealing(any(), any(), anyString());
    }

    /**
     * Nakon konačnog odbijanja ključ je obrisan, pa retry renderira obavijest iznova (npr. sada kao
     * PDF/A) i zamrzava je pod novim ključem — spremljeni stari bajtovi se ne šalju.
     */
    @Test
    void fileRegistration_afterRejection_rendersNoticeAgain() throws Exception {
        givenSubjektAndPredmetCreated();
        givenSealing();
        EgopPismenoEntity izlazno = EgopPismenoEntity.create(submission.getSubmissionId(),
                StrDocumentType.DODJELA.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2", "102", false);
        izlazno.replacePdf("stara-dodjela".getBytes());
        when(store.findPismeno(submission.getSubmissionId(), StrDocumentType.DODJELA.vrstaPismenaNaziv(),
                EgopPismenoEntity.ACT_REF_REGISTRACIJA)).thenReturn(Optional.of(izlazno));
        byte[] nova = "nova-dodjela".getBytes();

        service.fileRegistration(submission, lessor, new EgopDocumentSupplier() {
            @Override
            public byte[] zahtjev(FilingReference filing) {
                return "zahtjev".getBytes();
            }

            @Override
            public byte[] obavijestODodjeli(FilingReference filing) {
                return nova;
            }
        });

        verify(store).freezeForSealing(eq(izlazno.getId()), eq(nova), anyString());
        verify(sealer).seal(nova, "kljuc-0000000001", "529-06/26-2");
    }

    /**
     * Retry akta koji je poslan na pečatiranje, a odgovor nije stigao (timeout): ne renderira se
     * ponovo i ide isti par bajtovi + ključ — servis tada vraća pečat iz prvog poziva.
     */
    @Test
    void fileAct_frozenButUnsealed_resendsSameBytesUnderSameKey() throws Exception {
        givenSealing();
        EgopPismenoEntity akt = givenAktSuspenzije();
        akt.applyPismeno(1003, "529-06/26-3", "103", false);
        akt.replacePdf("zamrznuti".getBytes());
        ReflectionTestUtils.setField(akt, "sealIdempotencyKey", "kljuc-iz-prvog-poziva");

        service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> {
            throw new AssertionError("zamrznuti akt se ne smije renderirati ponovo");
        });

        verify(sealer).seal("zamrznuti".getBytes(), "kljuc-iz-prvog-poziva", "529-06/26-3");
        verify(store, never()).freezeForSealing(any(), any(), anyString());
        verify(store, never()).replacePdf(any(), any());
    }

    /** Konačno odbijanje (4xx, retryable=false): pečata nema, ključ se briše za novi pokušaj. */
    @Test
    void fileAct_nonRetryableRejection_clearsKeyForNextAttempt() throws Exception {
        givenSealing();
        when(sealer.seal(any(), anyString(), any())).thenThrow(
                new EsealException(422, "PDF_NOT_PDFA", "trace-1", false, null));
        EgopPismenoEntity akt = givenAktSuspenzije();

        assertThrows(EgopSealingException.class,
                () -> service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.empty()));

        verify(store).clearSealKey(akt.getId(), "kljuc-0000000001");
    }

    /** Timeout ili 503: servis je možda pečatirao — ključ i bajtovi ostaju za replay. */
    @Test
    void fileAct_retryableFailure_keepsKey() throws Exception {
        givenSealing();
        when(sealer.seal(any(), anyString(), any())).thenThrow(
                new EsealException(0, "TRANSPORT", null, true, "SocketTimeoutException"));
        EgopPismenoEntity akt = givenAktSuspenzije();

        assertThrows(EgopSealingException.class,
                () -> service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.empty()));

        verify(store, never()).clearSealKey(any(), any());
    }

    /** Paralelni tok je akt već priložio: ovaj ga ne prilaže drugi put. */
    @Test
    void fileAct_attachedMeanwhileByParallelFlow_doesNotAttachAgain() throws Exception {
        givenSealing();
        when(store.applySeal(any(), any(), any(), any(), any(), any())).thenReturn(false);
        EgopPismenoEntity akt = givenAktSuspenzije();

        assertThrows(EgopSealingException.class,
                () -> service.fileAct(akt, submission, lessor, "MINT\\str-svc", filing -> Optional.empty()));

        verify(egopClient, never()).kreirajDokumentZaPismeno(any());
        verify(kpOtprema, never()).otpremi(any());
    }

}
