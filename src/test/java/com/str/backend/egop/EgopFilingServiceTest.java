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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgopFilingServiceTest {

    private EgopClient egopClient;
    private EgopFilingStore store;
    private EgopFilingService service;

    private SubmissionEntity submission;
    private LessorEntity lessor;

    @BeforeEach
    void setUp() {
        egopClient = mock(EgopClient.class);
        store = mock(EgopFilingStore.class);
        when(store.readSubjektOznaka(any())).thenReturn(Optional.empty());
        when(store.storeSubjektOznaka(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(store.findPismeno(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(store.savePismeno(any())).thenAnswer(inv -> inv.getArgument(0));

        when(egopClient.getVrstePoslovnihSubjekata()).thenReturn(Map.of("Fizička osoba", "3", "Pravna osoba", "2"));
        when(egopClient.getVrstePredmeta()).thenReturn(Map.of("Izdavanje Registracijskog broja", "9282"));
        when(egopClient.getUstroj()).thenReturn(Map.of("MINISTARSTVO TURIZMA", 559));
        when(egopClient.getVrstePismena()).thenReturn(Map.of(
                "Zahtjev za registracijski broj", "101",
                "Obavijest o dodjeli registracijskog broja", "102"));

        service = new EgopFilingService(egopClient, store, properties("MINT", "str-svc"), "",
                "Izdavanje Registracijskog broja",
                "Zahtjev za registracijski broj",
                "Obavijest o dodjeli registracijskog broja",
                "MINISTARSTVO TURIZMA");

        lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", "ana@example.com");
        lessor.setLessorOib("12345678901");
        submission = SubmissionEntity.create(null, lessor.getLessorId(), null, null, null, null);
    }

    private static EgopProperties properties(String appDomain, String appUsername) {
        return new EgopProperties(true, "ntlm-user", "ntlm-pass", appDomain, appUsername,
                null, null,
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
        assertEquals(new FilingReference("334-01/26-01/55", "529-06/26-1"), seenZahtjev.get());
        // Izlazno pismeno ima vlastiti URBROJ — dokument mu se mora graditi na toj oznaci,
        // ne na oznaci zahtjeva (raniji kod je prilagao istu datoteku oba puta).
        assertEquals(new FilingReference("334-01/26-01/55", "529-06/26-2"), seenDodjela.get());
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
        service = new EgopFilingService(egopClient, store, properties("MINT", "str-svc"), "MINT\\ivan.ivic",
                "Izdavanje Registracijskog broja",
                "Zahtjev za registracijski broj",
                "Obavijest o dodjeli registracijskog broja",
                "MINISTARSTVO TURIZMA");
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
                EgopPismenoEntity.Smjer.ULAZNO, 1001, "529-06/26-1");
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
