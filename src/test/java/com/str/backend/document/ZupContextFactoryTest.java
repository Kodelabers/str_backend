package com.str.backend.document;

import com.str.backend.domain.RnStatus;
import com.str.backend.rn.dto.RnDetailDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ZupContextFactoryTest {

    private static final String RN = "HR180000123456789001";

    private static final DocumentProperties.Tijelo TIJELO = new DocumentProperties.Tijelo(
            "Ministarstvo turizma i sporta", "87892589782", null, "Zagreb", null,
            "članka 46. Zakona o ugostiteljskoj djelatnosti");

    private final DocumentLabels labels = new DocumentLabels();

    /**
     * Datum na aktu je „10. rujna 2026." — genitiv. Daje ga CLDR za {@code hr} u
     * format-kontekstu; da promjena JDK-a ne vrati tiho nominativ („rujan"), svih 12 mjeseci je
     * prikovano.
     */
    @ParameterizedTest
    @CsvSource({
            "1, 09. siječnja 2026.",
            "2, 09. veljače 2026.",
            "3, 09. ožujka 2026.",
            "4, 09. travnja 2026.",
            "5, 09. svibnja 2026.",
            "6, 09. lipnja 2026.",
            "7, 09. srpnja 2026.",
            "8, 09. kolovoza 2026.",
            "9, 09. rujna 2026.",
            "10, 09. listopada 2026.",
            "11, 09. studenoga 2026.",
            "12, 09. prosinca 2026."
    })
    void dates_useCroatianGenitiveMonth(int mjesec, String ocekivano) {
        Map<String, String> ctx = factory(false).forRn(StrDocumentType.DODJELA,
                detail(LocalDate.of(2026, mjesec, 9)), null, FilingReference.NONE);

        assertThat(ctx.get("rn.datumIzdavanja")).isEqualTo(ocekivano);
    }

    @Test
    void frameKeys_forHeaderAndSignature() {
        Map<String, String> ctx = factory(false).forRn(StrDocumentType.DODJELA,
                detail(LocalDate.of(2026, 9, 9)), null,
                new FilingReference("334-06/26-10/1", "529-06-03/01-26-2", 21748084));

        assertThat(ctx.get("tijelo.nazivVelikim")).isEqualTo("MINISTARSTVO TURIZMA I SPORTA");
        assertThat(ctx.get("akt.jopRedak")).isEqualTo("P/21748084");
        assertThat(ctx.get("akt.klasaRedak")).isEqualTo("KLASA: 334-06/26-10/1");
        assertThat(ctx.get("objekt.skupina"))
                .isEqualTo("Objekti u kojima se pružaju ugostiteljske usluge u domaćinstvu");
        // Potpis je naziv tijela; neupisano ime službene osobe nije greška pa ne nosi marker.
        assertThat(ctx.get("potpisnik.ime")).isEmpty();
    }

    @Test
    void jopRedak_isEmpty_beforeFiling() {
        Map<String, String> ctx = factory(false).forRn(StrDocumentType.DODJELA,
                detail(LocalDate.of(2026, 9, 9)), null, new FilingReference("334-06/26-10/1", null));

        assertThat(ctx.get("akt.jopRedak")).isEmpty();
    }

    /** Bez pečata nema ni broja zapisa — ne smije nastati neprovjerljiv broj. */
    @Test
    void sealKeys_absent_whileSealIsDisabled() {
        Map<String, String> ctx = factory(false).forRn(StrDocumentType.DODJELA,
                detail(LocalDate.of(2026, 9, 9)), null, FilingReference.NONE);

        assertThat(ctx.keySet()).noneMatch(k -> k.startsWith("epecat."));
    }

    @Test
    void sealKeys_present_whenSealIsEnabled() {
        Map<String, String> ctx = factory(true).forRn(StrDocumentType.DODJELA,
                detail(LocalDate.of(2026, 9, 9)), null, FilingReference.NONE);

        assertThat(ctx.get("epecat.algoritam")).isEqualTo("SHA256withRSA");
        assertThat(ctx.get("epecat.kontrolniBroj")).matches("\\d{8}");
        assertThat(UUID.fromString(ctx.get("epecat.brojZapisa"))).isNotNull();
        assertThat(ctx.get("epecat.qr")).isEqualTo("https://provjera.example.hr/?zapis="
                + ctx.get("epecat.brojZapisa") + "&kb=" + ctx.get("epecat.kontrolniBroj"));
        assertThat(ctx.get("epecat.provjera"))
                .startsWith("Na internet adresi https://provjera.example.hr/ možete");
        assertThat(ctx.get("epecat.potvrda")).contains("Ministarstvo turizma i sporta potvrđuje");
    }

    /**
     * Skupina se čita iz samog RB-a (HR + županija + <b>skupina</b> + vrsta + 12), ne iz
     * hardkodiranog natpisa — inače bi akt tvrdio domaćinstvo i za RB druge skupine.
     */
    @Test
    void group_isReadFromRegistrationNumber() {
        Map<String, String> ctx = factory(false).forRn(StrDocumentType.DODJELA,
                detail(LocalDate.of(2026, 9, 9), "HR180000123456789001"), null,
                FilingReference.NONE);

        assertThat(ctx.get("objekt.skupina"))
                .isEqualTo("Objekti u kojima se pružaju ugostiteljske usluge u domaćinstvu");
    }

    @Test
    void unknownGroup_showsVisibleMarker() {
        Map<String, String> ctx = factory(false).forRn(StrDocumentType.DODJELA,
                detail(LocalDate.of(2026, 9, 9), "HR180700123456789001"), null,
                FilingReference.NONE);

        assertThat(ctx.get("objekt.skupina")).isEqualTo("[nepoznata skupina: 07]");
    }

    /** Dodjela nosi datum izdavanja; akti životnog ciklusa datum rendera. */
    @Test
    void actDate_isIssueDateForDodjela_todayForOthers() {
        LocalDate izdano = LocalDate.of(2026, 3, 1);
        String danas = LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("dd. MMMM yyyy.", HR_LOCALE));

        assertThat(factory(false).forRn(StrDocumentType.DODJELA, detail(izdano), null,
                FilingReference.NONE).get("akt.datum")).isEqualTo("01. ožujka 2026.");
        assertThat(factory(false).forRn(StrDocumentType.SUSPENZIJA, detail(izdano), null,
                FilingReference.NONE).get("akt.datum")).isEqualTo(danas);
    }

    /** Redak adrese nosi natpis, pa nepoznata adresa ne ostavlja goli „Adresa: ,". */
    @Test
    void addressLine_isEmptyWithoutAddress() {
        Map<String, String> ctx = factory(false).forRn(StrDocumentType.PRIGOVOR,
                detail(LocalDate.of(2026, 9, 9)), null, FilingReference.NONE);

        assertThat(ctx.get("stranka.adresaRedak")).isEmpty();

        ZupContextFactory.putStrankaAdresa(ctx, "Ilica", "1", "Zagreb", "10000");

        assertThat(ctx.get("stranka.adresaRedak")).isEqualTo("Adresa: Ilica 1, 10000 Zagreb");
    }

    /** Pečat je pečat tijela — podnesak stranke mu ni ne gradi podatke. */
    @Test
    void sealKeys_absentForPartySubmission_evenWhenEnabled() {
        Map<String, String> ctx = factory(true).forRn(StrDocumentType.PRIGOVOR,
                detail(LocalDate.of(2026, 9, 9)), null, FilingReference.NONE);

        assertThat(ctx.keySet()).noneMatch(k -> k.startsWith("epecat."));
    }

    /** Bez URL-a portala nema QR sadržaja, a oznaka o nekonfiguriranom URL-u ostaje vidljiva. */
    @Test
    void qrContent_absentWithoutVerificationUrl() {
        DocumentProperties.Epecat bezUrla =
                new DocumentProperties.Epecat(true, "CN=Fina RDC 2020", "CN=PEČAT", null, null);
        Map<String, String> ctx = new ZupContextFactory(
                new DocumentProperties(TIJELO, null, bezUrla, Map.of(), false), labels)
                .forRn(StrDocumentType.DODJELA, detail(LocalDate.of(2026, 9, 9)), null,
                        FilingReference.NONE);

        assertThat(ctx).doesNotContainKey("epecat.qr");
        assertThat(ctx.get("epecat.provjera"))
                .contains("[nije konfigurirano: str.documents.epecat.url-provjere]");
    }

    private ZupContextFactory factory(boolean pecat) {
        DocumentProperties.Epecat epecat = pecat
                ? new DocumentProperties.Epecat(true, "CN=Fina RDC 2020", "CN=PEČAT", null,
                        "https://provjera.example.hr/")
                : new DocumentProperties.Epecat(false, null, null, null, null);
        return new ZupContextFactory(
                new DocumentProperties(TIJELO, null, epecat, Map.of(), false), labels);
    }

    private static final java.util.Locale HR_LOCALE = java.util.Locale.forLanguageTag("hr");

    private static RnDetailDto detail(LocalDate issueDate) {
        return detail(issueDate, RN);
    }

    private static RnDetailDto detail(LocalDate issueDate, String rn) {
        return new RnDetailDto(rn, RnStatus.ACTIVE,
                issueDate, issueDate, null,
                null, null, null, UUID.randomUUID(),
                UUID.randomUUID(), "Grad Zagreb", "Zagreb", null, "Ilica", "1",
                "Apartman Sunce", "Apartman", 4, "3*",
                UUID.randomUUID(), "Ana", "Anić", null, "ana@example.com", "98765432109",
                false, null, null, null,
                null, null, null, null, null);
    }
}
