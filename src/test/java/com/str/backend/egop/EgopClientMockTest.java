package com.str.backend.egop;

import com.str.backend.request.SubmissionRepository;
import hr.infodom.egov.pismeno.KreirajPismeno2;
import hr.infodom.egov.pismeno.PismenoBasicInfo2;
import hr.infodom.egov.predmet.KreirajPredmet2;
import hr.infodom.egov.predmet.PredmetBasicInfo2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mock mora vratiti ono što mu alokator dodijeli — ranije je sam brojao, globalno, pa je samo
 * prva registracija nakon pokretanja dobila urudžbene brojeve 1 i 2.
 */
class EgopClientMockTest {

    private SubmissionRepository submissions;
    private EgopPismenoRepository pismena;
    private EgopClientMock client;

    @BeforeEach
    void setUp() {
        submissions = mock(SubmissionRepository.class);
        pismena = mock(EgopPismenoRepository.class);
        LocalFilingNumberAllocator allocator =
                new LocalFilingNumberAllocator(submissions, pismena, new MockEnvironment(), "MOCK-", "334-01", "529-06");
        client = new EgopClientMock(allocator, false);
    }

    private KreirajPismeno2 pismenoRequest(int uredskaGodina, int rbrSpisa) {
        KreirajPismeno2 request = new KreirajPismeno2();
        request.setUredskaGodina((short) uredskaGodina);
        request.setRbrSpisa(rbrSpisa);
        return request;
    }

    @Test
    void kreirajPismeno2_numbersRestartInEachPredmet() {
        when(pismena.countFiledInPredmet(2026, 11)).thenReturn(0L, 1L);
        when(pismena.countFiledInPredmet(2026, 12)).thenReturn(0L);

        assertThat(client.kreirajPismeno2(pismenoRequest(2026, 11)).getUrBroj())
                .isEqualTo("MOCK-529-06/26-1");
        assertThat(client.kreirajPismeno2(pismenoRequest(2026, 11)).getUrBroj())
                .isEqualTo("MOCK-529-06/26-2");
        assertThat(client.kreirajPismeno2(pismenoRequest(2026, 12)).getUrBroj())
                .isEqualTo("MOCK-529-06/26-1");
    }

    /**
     * Nekad je {@code jop} bio {@code 1000 + globalni brojač} pa jedinstven po konstrukciji;
     * s brojanjem po predmetu prvi akt svakog predmeta ima isti redni broj, pa {@code jop} mora
     * uzeti i predmet u obzir — inače su tragovi {@code attachPdfOnce} nečitljivi.
     */
    @Test
    void kreirajPismeno2_jopDiffersAcrossPredmeti_forSameRedniBroj() {
        when(pismena.countFiledInPredmet(2026, 11)).thenReturn(0L);
        when(pismena.countFiledInPredmet(2026, 12)).thenReturn(0L);

        PismenoBasicInfo2 prvi = client.kreirajPismeno2(pismenoRequest(2026, 11));
        PismenoBasicInfo2 drugi = client.kreirajPismeno2(pismenoRequest(2026, 12));

        assertThat(prvi.getUrBroj()).isEqualTo(drugi.getUrBroj());
        assertThat(prvi.getJop()).isNotEqualTo(drugi.getJop());
    }

    @Test
    void kreirajPredmet2_takesGodinaRbrAndKlasaFromAllocator() {
        int godina = LocalDate.now().getYear();
        when(submissions.maxEgopRbrPredmeta(godina)).thenReturn(7);

        PredmetBasicInfo2 predmet = client.kreirajPredmet2(new KreirajPredmet2());

        assertThat(predmet.isOperationSucceeded()).isTrue();
        assertThat(predmet.getUredskaGodina()).isEqualTo(godina);
        assertThat(predmet.getRbrPredmeta()).isEqualTo(8);
        assertThat(predmet.getKlasifikacijskaOznaka())
                .isEqualTo("MOCK-334-01/" + (godina % 100) + "-01/8");
    }
}
