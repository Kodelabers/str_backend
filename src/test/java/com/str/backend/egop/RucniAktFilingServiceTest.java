package com.str.backend.egop;

import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.kp.api.KpAkt;
import com.str.backend.kp.api.KpPorukaService;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RucniAkt;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RucniAktFilingServiceTest {

    private static final String RN = "HR180000123456789001";
    private static final UUID LOG = UUID.randomUUID();
    private static final UUID SUBMISSION = UUID.randomUUID();
    private static final RucniAkt RUCNI = new RucniAkt("334-06/26-01/7", "2181-26-2", "Splitsko-dalmatinska županija",
            "40781519492", "Upravni odjel", 17);
    private static final byte[] PDF = "%PDF akt".getBytes();

    private final StrDocumentService documents = mock(StrDocumentService.class);
    private final EgopPismenoRepository pismena = mock(EgopPismenoRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final PrilogService prilozi = mock(PrilogService.class);
    private final KpPorukaService kp = mock(KpPorukaService.class);
    private final RucniAktFilingService service = new RucniAktFilingService(documents, pismena, rnRepository, prilozi, kp);
    private final RnDetailDto detail = mock(RnDetailDto.class);

    private final RnLifecycleEvent event = new RnLifecycleEvent(LOG, RN, RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED,
            RnTrigger.INSPECTION, "INTERNAL:12345678903", "nalaz");

    @BeforeEach
    void setUp() {
        when(documents.render(StrDocumentType.PRIJEDLOG_SUSPENZIJE, RN, "nalaz")).thenReturn(PDF);
        when(pismena.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(detail));
        when(detail.lessorOib()).thenReturn("12312312316");
        when(detail.legalEntityOwner()).thenReturn(false);
        when(prilozi.zaVlasnika(PrilogVlasnik.prijelaz(LOG))).thenReturn(List.of());
    }

    @Test
    void zapisujeRucniSyncedAktIBiljeziZaKp() {
        EgopPismenoEntity akt = service.zapisi(event, SUBMISSION, StrDocumentType.PRIJEDLOG_SUSPENZIJE, LOG.toString(),
                RUCNI);
        verify(kp, never()).zabiljezi(any());
        service.zabiljeziZaKp(event, StrDocumentType.PRIJEDLOG_SUSPENZIJE, akt, RUCNI);

        assertThat(akt.isRucno()).isTrue();
        assertThat(akt.getStatus()).isEqualTo(EgopSyncStatus.SYNCED);
        assertThat(akt.getKlasa()).isEqualTo("334-06/26-01/7");
        assertThat(akt.getUrBroj()).isEqualTo("2181-26-2");
        assertThat(akt.getJop()).isNull();
        assertThat(akt.getPdfContent()).isEqualTo(PDF);

        ArgumentCaptor<KpAkt> kpAkt = ArgumentCaptor.forClass(KpAkt.class);
        verify(kp).zabiljezi(kpAkt.capture());
        KpAkt k = kpAkt.getValue();
        assertThat(k.pismenoId()).isEqualTo(akt.getId());
        assertThat(k.oib()).isEqualTo("12312312316");
        assertThat(k.vrstaAkta()).isEqualTo("PRIJEDLOG_SUSPENZIJE");
        assertThat(k.izdavateljZuRb()).isEqualTo(17);
        assertThat(k.klasa()).isEqualTo("334-06/26-01/7");
        assertThat(k.urBroj()).isEqualTo("2181-26-2");
        assertThat(k.naslov()).isEqualTo(StrDocumentType.PRIJEDLOG_SUSPENZIJE.vrstaPismenaNaziv() + " " + RN);
        assertThat(k.sadrzaj()).contains("KLASA: 334-06/26-01/7, URBROJ: 2181-26-2").endsWith("SPLITSKO-DALMATINSKA ŽUPANIJA");
        assertThat(k.velicinaAkta()).isEqualTo(PDF.length);
    }

    /** U pretinac idu samo prilozi službenika (uz izlazni akt), ne podnesci stranke. */
    @Test
    void prilozi_samoSluzbenikovi() {
        PrilogSazetak sluzbenik = prilog(1L, "INTERNAL:12345678903");
        PrilogSazetak stranka = prilog(2L, "NIAS:12312312316");
        when(prilozi.zaVlasnika(PrilogVlasnik.prijelaz(LOG))).thenReturn(List.of(sluzbenik, stranka));

        service.zabiljeziZaKp(event, StrDocumentType.PRIJEDLOG_SUSPENZIJE, akt(), RUCNI);

        ArgumentCaptor<KpAkt> kpAkt = ArgumentCaptor.forClass(KpAkt.class);
        verify(kp).zabiljezi(kpAkt.capture());
        assertThat(kpAkt.getValue().prilozi()).extracting(KpAkt.Prilog::id).containsExactly(1L);
    }

    /** Pravna osoba i non-EU (bez OIB-a) dobivaju akt samo e-poštom. */
    @Test
    void pravnaOsobaINonEu_bezKp() {
        when(detail.legalEntityOwner()).thenReturn(true);
        service.zabiljeziZaKp(event, StrDocumentType.PRIJEDLOG_SUSPENZIJE, akt(), RUCNI);

        when(detail.legalEntityOwner()).thenReturn(false);
        when(detail.lessorOib()).thenReturn(null);
        service.zabiljeziZaKp(event, StrDocumentType.PRIJEDLOG_SUSPENZIJE, akt(), RUCNI);

        when(rnRepository.findDetail(RN)).thenReturn(Optional.empty());
        service.zabiljeziZaKp(event, StrDocumentType.PRIJEDLOG_SUSPENZIJE, akt(), RUCNI);

        verify(kp, never()).zabiljezi(any());
    }

    private static EgopPismenoEntity akt() {
        return EgopPismenoEntity.rucniAkt(SUBMISSION, RN, LOG.toString(),
                StrDocumentType.PRIJEDLOG_SUSPENZIJE.vrstaPismenaNaziv(), PDF, RUCNI.klasa(), RUCNI.urBroj());
    }

    private static PrilogSazetak prilog(long id, String ucitao) {
        PrilogSazetak p = mock(PrilogSazetak.class);
        when(p.getId()).thenReturn(id);
        when(p.getUcitao()).thenReturn(ucitao);
        when(p.getNazivDatoteke()).thenReturn("p" + id + ".pdf");
        when(p.getContentType()).thenReturn("application/pdf");
        when(p.getVelicina()).thenReturn(1000L);
        return p;
    }
}
