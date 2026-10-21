package com.str.backend.egop;

import com.str.backend.document.FilingReference;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.request.SubmissionEntity;
import hr.infodom.egov.pismeno.DokumentInfo;
import hr.infodom.egov.pismeno.KreirajDokumentZaPismeno;
import hr.infodom.egov.pismeno.KreirajPismeno2;
import hr.infodom.egov.pismeno.PismenoBasicInfo2;
import hr.infodom.egov.predmet.KreirajPredmet2;
import hr.infodom.egov.predmet.OdrediRjesavatelja;
import hr.infodom.egov.predmet.PredmetBasicInfo2;
import hr.infodom.egov.predmet.PredmetInfo;
import hr.infodom.egov.subjekt.KreirajSubjekta;
import hr.infodom.egov.subjekt.SubjektInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private EgopFilingService service;

    private SubmissionEntity submission;
    private LessorEntity lessor;

    @BeforeEach
    void setUp() {
        egopClient = mock(EgopClient.class);
        store = mock(EgopFilingStore.class);
        kpOtprema = mock(EgopKpOtpremaService.class);
        when(store.readSubjektOznaka(any())).thenReturn(Optional.empty());
        when(store.storeSubjektOznaka(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(store.findPismeno(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(store.savePismeno(any())).thenAnswer(inv -> inv.getArgument(0));

        when(egopClient.getVrstePoslovnihSubjekata()).thenReturn(Map.of("Fizička osoba", "3", "Pravna osoba", "2"));
        when(egopClient.getVrstePredmeta()).thenReturn(Map.of("Izdavanje Registracijskog broja", "9282",
                "Suspenzija, povlačenje i reaktiviranje dodjela registracijskog STR broja", "9283"));
        when(egopClient.getUstroj()).thenReturn(Map.of("MINISTARSTVO TURIZMA", 559));
        when(egopClient.getVrstePismena()).thenReturn(Map.of(
                "Zahtjev za registracijski broj", "101",
                "Obavijest o dodjeli registracijskog broja", "102"));

        service = new EgopFilingService(egopClient, store, resolver(), kpOtprema, properties("MINT", "str-svc"),
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

        PismenoBasicInfo2 ulazno = pismeno(1001, "529-06/26-1");
        PismenoBasicInfo2 izlazno = pismeno(1002, "529-06/26-2");
        when(egopClient.kreirajPismeno2(any())).thenReturn(ulazno, izlazno);
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

        ArgumentCaptor<KreirajPismeno2> pismenoCaptor = ArgumentCaptor.forClass(KreirajPismeno2.class);
        verify(egopClient, times(2)).kreirajPismeno2(pismenoCaptor.capture());
        assertEquals("101", pismenoCaptor.getAllValues().get(0).getVrstaPismena());
        assertEquals("102", pismenoCaptor.getAllValues().get(1).getVrstaPismena());
        // Oba pismena moraju nositi predmet u koji idu. Kad eGOP nije uključen, urudžbeni broj
        // dodjeljuje LocalFilingNumberAllocator brojanjem pismena UNUTAR tog predmeta — regresija
        // na 0/null ovdje tiho svali sve predmete u isti brojač i razbije "podnesak 1, akt 2",
        // a da nijedan drugi test ne padne.
        for (KreirajPismeno2 poslano : pismenoCaptor.getAllValues()) {
            assertEquals(55, poslano.getRbrSpisa());
            assertEquals((short) 2026, poslano.getUredskaGodina());
        }

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
        service = new EgopFilingService(egopClient, store, resolver(), kpOtprema, properties("MINT", "str-svc"),
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

        PismenoBasicInfo2 izlazno = pismeno(1002, "529-06/26-2");
        when(egopClient.kreirajPismeno2(any())).thenReturn(izlazno);
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());

        EgopFilingService.FilingResult result = service.fileRegistration(submission, lessor, documents("pdf".getBytes()));

        assertEquals("KLASA: 334-01/26-01/55, URBROJ: 529-06/26-1", result.filingNumber());
        assertEquals(EgopSyncStatus.SYNCED, submission.getEgopSyncStatus());
        verify(egopClient, never()).dohvatiPodatkeSubjekta(any());
        verify(egopClient, never()).kreirajSubjekta(any());
        verify(egopClient, never()).kreirajPredmet2(any());
        // samo izlazno pismeno se kreira — ulazno već postoji
        verify(egopClient, times(1)).kreirajPismeno2(any());
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

        when(egopClient.kreirajPismeno2(any()))
                .thenReturn(pismeno(1001, "529-06/26-1"), pismeno(1002, "529-06/26-2"));
        when(egopClient.kreirajDokumentZaPismeno(any())).thenReturn(new DokumentInfo());
    }

    private PismenoBasicInfo2 pismeno(int jop, String urBroj) {
        PismenoBasicInfo2 p = new PismenoBasicInfo2();
        p.setOperationSucceeded(true);
        p.setJop(jop);
        p.setUrBroj(urBroj);
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
        when(egopClient.kreirajPismeno2(any())).thenReturn(pismeno(1003, "529-06/26-3"));
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
        when(egopClient.kreirajPismeno2(any())).thenReturn(pismeno(1003, "529-06-03/01-27-5-1"));
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

        ArgumentCaptor<KreirajPismeno2> pismenoRequest = ArgumentCaptor.forClass(KreirajPismeno2.class);
        verify(egopClient).kreirajPismeno2(pismenoRequest.capture());
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
        verify(egopClient, never()).kreirajPismeno2(any());
        assertEquals(null, seen.get(), "konačni dokument se ne renderira");
        ArgumentCaptor<KreirajDokumentZaPismeno> dokument = ArgumentCaptor.forClass(KreirajDokumentZaPismeno.class);
        verify(egopClient).kreirajDokumentZaPismeno(dokument.capture());
        assertEquals("snimak", new String(dokument.getValue().getAttachment()));
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
        when(egopClient.kreirajPismeno2(any())).thenReturn(pismeno(1004, "529-06-03/01-27-5-2"));
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

}
