package com.str.backend.attachment;

import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopClient;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopTransportException;
import hr.infodom.egov.pismeno.DokumentInfo;
import hr.infodom.egov.pismeno.KreirajPrilogIPridruziDokument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private PrilogEgopService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(PrilogRepository.class);
        egopClient = mock(EgopClient.class);
        when(egopClient.getVrstePriloga()).thenReturn(Map.of("Prilog", 7));
        when(egopClient.kreirajPrilogIPridruziDokument(any())).thenReturn(new DokumentInfo());
        when(repository.findSadrzaj(anyLong())).thenAnswer(inv -> ("sadrzaj-" + inv.getArgument(0)).getBytes());
        service = new PrilogEgopService(repository, egopClient, "", "Prilog");
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

    /** Pad usred popisa: priloženi ostaju označeni, ostali se ne označavaju i iznimka ide u retry. */
    @Test
    void failureMidway_marksOnlyAttached_andPropagates() throws Exception {
        UUID logId = UUID.randomUUID();
        EgopPismenoEntity akt = akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003);
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK),
                TestPrilog.vezan(12, VrstaVlasnika.RN_LOG, logId, "b.pdf", SLUZBENIK)));
        when(egopClient.kreirajPrilogIPridruziDokument(any()))
                .thenReturn(new DokumentInfo())
                .thenThrow(new EgopTransportException("timeout", null));

        assertThatThrownBy(() -> service.prilozi(akt, "ana.sluzbenica")).isInstanceOf(EgopTransportException.class);

        verify(repository).markEgopPrilozeno(eq(11L), eq(1003), eq(1), any());
        verify(repository, never()).markEgopPrilozeno(eq(12L), anyInt(), anyInt(), any());
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
        service = new PrilogEgopService(repository, egopClient, "42", "Prilog");
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK)));

        service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x");

        ArgumentCaptor<KreirajPrilogIPridruziDokument> sent = ArgumentCaptor.forClass(KreirajPrilogIPridruziDokument.class);
        verify(egopClient).kreirajPrilogIPridruziDokument(sent.capture());
        assertThat(sent.getValue().getIdVrstaPriloga()).isEqualTo(42);
        verify(egopClient, never()).getVrstePriloga();
    }

    @Test
    void unresolvableType_failsLoudly_beforeSending() throws Exception {
        service = new PrilogEgopService(repository, egopClient, "", "Nepostojeća vrsta");
        UUID logId = UUID.randomUUID();
        when(repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(List.of(
                TestPrilog.vezan(11, VrstaVlasnika.RN_LOG, logId, "a.pdf", SLUZBENIK)));

        assertThatThrownBy(() -> service.prilozi(akt(logId, EgopPismenoEntity.Smjer.IZLAZNO, 1003), "x"))
                .isInstanceOf(EgopBadRequestException.class)
                .hasMessageContaining("str.egop.prilog.vrsta-sifra");
        verify(egopClient, never()).kreirajPrilogIPridruziDokument(any());
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

    private static EgopPismenoEntity akt(UUID logId, EgopPismenoEntity.Smjer smjer, int jop) {
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), "HR1", logId.toString(),
                "Obavijest o suspenziji registracijskog broja", smjer, new byte[]{1});
        akt.applyPismeno(jop, "529-06/26-3", "103", false);
        return akt;
    }
}
