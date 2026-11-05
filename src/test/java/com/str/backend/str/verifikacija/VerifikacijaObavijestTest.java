package com.str.backend.str.verifikacija;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VerifikacijaObavijestTest {

    private static final String PREDLOZAK = EturizamVerifikacijaProperties.Obavijesti.DEFAULT_TEKST;

    @Test
    void tekst_fillsPlaceholders() {
        String tekst = VerifikacijaObavijest.tekst(PREDLOZAK, "HR180001000000000123",
                VerifikacijaObavijest.opisObjekta("Apartman Marija", "Makarska", "1448035"));

        assertThat(tekst).isEqualTo("Smještajnom objektu „Apartman Marija“ (Makarska, ID 1448035) dodijeljen je "
                + "registracijski broj HR180001000000000123. Objekt nije verificiran – potrebna je žurna verifikacija.");
    }

    @Test
    void opisObjekta_withoutNameOrPlace_keepsId() {
        assertThat(VerifikacijaObavijest.opisObjekta(null, null, "1448035")).isEqualTo("(ID 1448035)");
        assertThat(VerifikacijaObavijest.opisObjekta("-", " Makarska ", "1448035")).isEqualTo("(Makarska, ID 1448035)");
        assertThat(VerifikacijaObavijest.opisObjekta(" Kuća ", "", "7")).isEqualTo("„Kuća“ (ID 7)");
    }

    @Test
    void tekst_truncatedTo1000Characters() {
        String dugiNaziv = "N".repeat(1500);

        String tekst = VerifikacijaObavijest.tekst(PREDLOZAK, "HR180001000000000123",
                VerifikacijaObavijest.opisObjekta(dugiNaziv, "Makarska", "1"));

        assertThat(tekst).hasSize(VerifikacijaObavijest.MAX_TEKST).endsWith("…");
    }

    @Test
    void skrati_doesNotSplitSurrogatePair() {
        // 998 znakova + emoji (2 UTF-16 jedinice) + rep: rez bi pao usred para.
        String value = "a".repeat(998) + "😀" + "bbbb";

        String skraceno = VerifikacijaObavijest.skrati(value, 1000);

        assertThat(skraceno).isEqualTo("a".repeat(998) + "…");
        assertThat(VerifikacijaObavijest.skrati("kratko", 1000)).isEqualTo("kratko");
    }

    @Test
    void podaci_isJsonWithIdentifiers() {
        assertThat(VerifikacijaObavijest.podaci("HR180001000000000123", 110735L, "1448035", 14L))
                .isEqualTo("{\"rn\":\"HR180001000000000123\",\"documentId\":110735,\"facilityId\":1448035,"
                        + "\"verificationRequestId\":14}");
    }

    @Test
    void properties_defaultsWhenUnset() {
        EturizamVerifikacijaProperties p = new EturizamVerifikacijaProperties(false, " ", null, null, null);

        assertThat(p.korisnik()).isEqualTo("STR");
        assertThat(p.obavijesti().enabled()).isFalse();
        assertThat(p.obavijesti().vrsta()).isEqualTo("ZURNA_VERIFIKACIJA");
        assertThat(p.obavijesti().tekst()).isEqualTo(PREDLOZAK);
        assertThat(p.retry().maxAttempts()).isEqualTo(10);
        assertThat(p.retry().backoffBase()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
