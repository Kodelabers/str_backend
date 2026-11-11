package com.str.backend.kp.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KpApiPropertiesTest {

    private static KpApiProperties props(Boolean enabled, String url, Integer tip, Map<String, Integer> poAktu) {
        return new KpApiProperties(enabled, url, "korisnik", "lozinka", tip, poAktu, null, null, null, null, null, null,
                null, null, null);
    }

    @Test
    void zadano_ugasenoIZadaneVrijednosti() {
        KpApiProperties p = props(null, " ", null, null);

        assertThat(p.enabled()).isFalse();
        assertThat(p.aktivno()).isFalse();
        assertThat(p.baseUrl()).isNull();
        assertThat(p.maxVelicina()).isEqualTo(KpApiProperties.DEFAULT_MAX_VELICINA);
        assertThat(p.batchSize()).isEqualTo(20);
        assertThat(p.maxAttempts()).isEqualTo(10);
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    /** Uključeno bez adrese ili šifre poruke nije aktivno — akt ide samo e-poštom. */
    @Test
    void aktivno_samoSAdresomISifrom() {
        assertThat(props(true, "https://kp", 1741, null).aktivno()).isTrue();
        assertThat(props(true, null, 1741, null).aktivno()).isFalse();
        assertThat(props(true, "https://kp", null, null).aktivno()).isFalse();
        assertThat(props(false, "https://kp", 1741, null).aktivno()).isFalse();
    }

    @Test
    void tipZa_iznimkaPoAktuIliZadani() {
        KpApiProperties p = props(true, "https://kp", 1741, Map.of("SUSPENZIJA", 1843));

        assertThat(p.tipZa("SUSPENZIJA")).isEqualTo(1843);
        assertThat(p.tipZa("REAKTIVACIJA")).isEqualTo(1741);
        assertThat(p.tipZa(null)).isEqualTo(1741);
    }

    @Test
    void odgoda_udvostrucujeDoNajvise() {
        KpApiProperties p = props(true, "https://kp", 1741, null);

        assertThat(p.odgoda(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.odgoda(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.odgoda(4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(p.odgoda(20)).isEqualTo(Duration.ofHours(2));
    }

    /** Bez korisnika ili lozinke slanje nije aktivno; lozinka se ne ispisuje. */
    @Test
    void prijava_obaveznaILozinkaSkrivena() {
        KpApiProperties bezLozinke = new KpApiProperties(true, "https://kp", "korisnik", null, 1741, null, null, null,
                null, null, null, null, null, null, null);
        KpApiProperties sLozinkom = props(true, "https://kp", 1741, null);

        assertThat(bezLozinke.aktivno()).isFalse();
        assertThat(bezLozinke.nedostaje()).isEqualTo("STR_KP_API_PASSWORD");
        assertThat(sLozinkom.toString()).doesNotContain("lozinka").contains("***");
    }
}
