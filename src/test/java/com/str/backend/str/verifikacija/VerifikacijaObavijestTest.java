package com.str.backend.str.verifikacija;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
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

    /** Predložak iz konfiguracije može biti dug — tekst se ipak skraćuje na 1000 znakova. */
    @Test
    void tekst_truncatedTo1000Characters() {
        String tekst = VerifikacijaObavijest.tekst("X".repeat(1500) + " {rn}", "HR180001000000000123", "(ID 1)");

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
    void ocisti_removesMarkupControlAndBidiCharacters() {
        assertThat(VerifikacijaObavijest.ocisti("<img src=x onerror=\"alert(1)\">", 100))
                .isEqualTo("img src=x onerror=alert(1)");
        assertThat(VerifikacijaObavijest.ocisti("Vila\r\n\tMare\u202E\u2066 & 'More'`", 100))
                .isEqualTo("Vila Mare More");
        assertThat(VerifikacijaObavijest.ocisti("a\u200Bb\u0000c", 100)).isEqualTo("a b c");
        assertThat(VerifikacijaObavijest.ocisti(null, 100)).isNull();
    }

    /** Dugačak naziv se skraćuje sam, pa RB i ID objekta uvijek ostaju u tekstu. */
    @Test
    void longName_isCappedSoRnAndIdSurvive() {
        String tekst = VerifikacijaObavijest.tekst(PREDLOZAK, "HR180001000000000123",
                VerifikacijaObavijest.opisObjekta("N".repeat(5000), "M".repeat(5000), "1448035"));

        assertThat(tekst).contains("HR180001000000000123", "ID 1448035").hasSizeLessThan(VerifikacijaObavijest.MAX_TEKST);
        assertThat(tekst).contains("„" + "N".repeat(VerifikacijaObavijest.MAX_DIO - 1) + "…“");
    }

    @Test
    void opis_cutsRowValuesAndSqlFromMessage() {
        RuntimeException pg = new RuntimeException("insert", new SQLException(
                "ERROR: new row for relation \"korisnicka_obavijest\" violates check constraint \"x\"\n"
                        + "  Detail: Failing row contains (1, 951001, Ana Anić, ...).", "23514"));
        RuntimeException h2 = new RuntimeException(new SQLException(
                "Check constraint violation: \"CK\"; SQL statement:\nINSERT INTO str.korisnicka_obavijest VALUES ('Ana')",
                "23513"));

        assertThat(VerifikacijaGreska.opis(pg))
                .isEqualTo("SQLException: ERROR: new row for relation \"korisnicka_obavijest\" violates check constraint \"x\"");
        assertThat(VerifikacijaGreska.opis(h2)).isEqualTo("SQLException: Check constraint violation: \"CK\"");
    }

    @Test
    void vrsta_classifiesPermissionInfrastructureAndData() {
        assertThat(VerifikacijaGreska.vrsta(new RuntimeException(new SQLException("permission denied", "42501"))))
                .isEqualTo(VerifikacijaGreska.Vrsta.BEZ_PRAVA);
        assertThat(VerifikacijaGreska.vrsta(new TransactionTimedOutException("x")))
                .isEqualTo(VerifikacijaGreska.Vrsta.INFRASTRUKTURA);
        assertThat(VerifikacijaGreska.vrsta(new RuntimeException(new SQLException("canceling statement", "57014"))))
                .isEqualTo(VerifikacijaGreska.Vrsta.INFRASTRUKTURA);
        assertThat(VerifikacijaGreska.vrsta(new RuntimeException(new SQLException("check", "23514"))))
                .isEqualTo(VerifikacijaGreska.Vrsta.PODACI);
    }

    @Test
    void properties_defaultsWhenUnset() {
        EturizamVerifikacijaProperties p = new EturizamVerifikacijaProperties(null, " ", null, null, null, null);

        assertThat(p.enabled()).isFalse();
        assertThat(p.korisnik()).isEqualTo("STR");
        assertThat(p.obavijesti().enabled()).isFalse();
        assertThat(p.obavijesti().vrsta()).isEqualTo("ZURNA_VERIFIKACIJA");
        assertThat(p.obavijesti().tekst()).isEqualTo(PREDLOZAK);
        assertThat(p.obavijesti().maxPrimatelja()).isEqualTo(200);
        assertThat(p.retry().maxAttempts()).isEqualTo(10);
        assertThat(p.retry().backoffBase()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.inlineTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.obavijesti().tekstNoviObjekt())
                .isEqualTo(EturizamVerifikacijaProperties.Obavijesti.DEFAULT_TEKST_NOVI_OBJEKT);
    }

    // ---------------------------------------------------------------- novi objekt uz predano rješenje

    @Test
    void tekstNovogObjekta_fillsPlaceholders() {
        String tekst = VerifikacijaObavijest.tekst(EturizamVerifikacijaProperties.Obavijesti.DEFAULT_TEKST_NOVI_OBJEKT,
                "HR170001000000000456", VerifikacijaObavijest.opisNovogObjekta("Apartman Ana", "Split"));

        assertThat(tekst).isEqualTo("Uz registracijski broj HR170001000000000456 priloženo je rješenje o kategorizaciji "
                + "za novi smještajni objekt „Apartman Ana“ (Split). Objekt nije upisan u eTurizam – potrebna je "
                + "žurna verifikacija. Rješenje unesite kroz modul Verifikacije, funkcionalnost Unos rješenja.");
    }

    /** Naziv i mjesto unosi iznajmljivač: čiste se, a bez oboje rečenica ostaje cijela. */
    @Test
    void opisNovogObjekta_sanitizedAndNeverEmpty() {
        assertThat(VerifikacijaObavijest.opisNovogObjekta("<b>Vila</b>", null)).isEqualTo("„bVila/b“");
        assertThat(VerifikacijaObavijest.opisNovogObjekta(null, " Split ")).isEqualTo("(Split)");
        assertThat(VerifikacijaObavijest.opisNovogObjekta("-", "")).isEqualTo("(bez naziva)");
    }

    @Test
    void podaciNovogObjekta_carryRnAndDecision() {
        assertThat(VerifikacijaObavijest.podaciNovogObjekta("HR170001000000000456",
                java.util.UUID.fromString("0f6c2c9e-6a43-4d8f-9b0d-6a1f3b1b7c55")))
                .isEqualTo("{\"rn\":\"HR170001000000000456\",\"decisionId\":\"0f6c2c9e-6a43-4d8f-9b0d-6a1f3b1b7c55\"}");
    }
}
