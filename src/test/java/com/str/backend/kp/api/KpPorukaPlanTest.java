package com.str.backend.kp.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KpPorukaPlanTest {

    private static final long MB = 1_000_000L;
    private static final long MAX = 3 * MB;

    private static KpAkt akt(long velicinaAkta, List<KpAkt.Prilog> prilozi) {
        return new KpAkt(UUID.randomUUID(), "HR170001000000000123", "12312312316", "SUSPENZIJA", 17,
                "Obavijest o suspenziji registracijskog broja HR170001000000000123", "Poštovani, …",
                "334-06/26-01/1", "2181-26-2", "obavijest.pdf", velicinaAkta, prilozi);
    }

    private static KpAkt.Prilog pdf(long id, long velicina) {
        return new KpAkt.Prilog(id, "prilog-" + id + ".pdf", "application/pdf", velicina);
    }

    @Test
    void aktBezPriloga_jednaPoruka() {
        List<KpPorukaPlan.Dio> dijelovi = KpPorukaPlan.izradi(akt(200_000, List.of()), MAX);

        assertThat(dijelovi).hasSize(1);
        assertThat(dijelovi.get(0).akt()).isTrue();
        assertThat(dijelovi.get(0).prilozi()).isEmpty();
        assertThat(dijelovi.get(0).naslov()).doesNotContain("(1/");
        assertThat(dijelovi.get(0).sadrzaj()).isEqualTo("Poštovani, …");
    }

    @Test
    void priloziKojiStanu_istaPoruka() {
        List<KpPorukaPlan.Dio> dijelovi = KpPorukaPlan.izradi(akt(200_000, List.of(pdf(1, 300_000), pdf(2, 300_000))), MAX);

        assertThat(dijelovi).singleElement().satisfies(d -> assertThat(d.prilozi()).containsExactly(1L, 2L));
    }

    /** Base64 povećava za trećinu: 1,5 + 1,5 MB priloga ne stane uz akt, pa ide u dvije poruke. */
    @Test
    void preveliko_dijeliSeNaVisePoruka() {
        List<KpPorukaPlan.Dio> dijelovi = KpPorukaPlan.izradi(
                akt(200_000, List.of(pdf(1, 1_500_000), pdf(2, 1_500_000), pdf(3, 100_000))), MAX);

        assertThat(dijelovi).extracting(KpPorukaPlan.Dio::dio).containsExactly(1, 2);
        assertThat(dijelovi.get(0).akt()).isTrue();
        assertThat(dijelovi.get(0).prilozi()).containsExactly(1L);
        assertThat(dijelovi.get(1).akt()).isFalse();
        assertThat(dijelovi.get(1).prilozi()).containsExactly(2L, 3L);
        assertThat(dijelovi.get(0).naslov()).endsWith("(1/2)");
        assertThat(dijelovi.get(1).naslov()).endsWith("(2/2)");
        assertThat(dijelovi.get(1).sadrzaj()).startsWith("Prilozi uz poruku „Obavijest o suspenziji");
    }

    /** Prilog koji nije PDF ili sam ne stane u poruku ne ide u pretinac, a prva poruka to kaže. */
    @Test
    void nePdfIPrevelikPrilog_izostavljeniUzNapomenu() {
        KpAkt.Prilog word = new KpAkt.Prilog(1, "ugovor.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 10_000);
        KpAkt.Prilog golem = pdf(2, 2_500_000);

        List<KpPorukaPlan.Dio> dijelovi = KpPorukaPlan.izradi(akt(200_000, List.of(word, golem, pdf(3, 1000))), MAX);

        assertThat(dijelovi).singleElement().satisfies(d -> {
            assertThat(d.prilozi()).containsExactly(3L);
            assertThat(d.sadrzaj()).contains(KpPorukaPlan.NAPOMENA + "ugovor.docx, prilog-2.pdf.");
        });
    }

    @Test
    void prevelikAkt_porukaBezAktaUzNapomenu() {
        List<KpPorukaPlan.Dio> dijelovi = KpPorukaPlan.izradi(akt(2_500_000, List.of()), MAX);

        assertThat(dijelovi).singleElement().satisfies(d -> {
            assertThat(d.akt()).isFalse();
            assertThat(d.sadrzaj()).endsWith(KpPorukaPlan.AKT_PREVELIK);
        });
    }

    @Test
    void ime_bezZabranjenihZnakova() {
        assertThat(KpPorukaPlan.ime("a/b\\c:d*e?f\"g<h>i|j\u0007.pdf")).isEqualTo("abcdefghij.pdf");
        assertThat(KpPorukaPlan.ime(" :/ ")).isEqualTo("prilog.pdf");
        assertThat(KpPorukaPlan.ime(null)).isEqualTo("prilog.pdf");
    }

    @Test
    void jePdf_poVrstiIliNazivu() {
        assertThat(KpPorukaPlan.jePdf(new KpAkt.Prilog(1, "x.bin", "application/pdf", 1))).isTrue();
        assertThat(KpPorukaPlan.jePdf(new KpAkt.Prilog(1, "X.PDF", null, 1))).isTrue();
        assertThat(KpPorukaPlan.jePdf(new KpAkt.Prilog(1, "x.pdf", "image/png", 1))).isFalse();
    }
}
