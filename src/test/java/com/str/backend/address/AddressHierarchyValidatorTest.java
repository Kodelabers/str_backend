package com.str.backend.address;

import com.str.backend.address.AddressHierarchyValidator.AddressSelection;
import com.str.backend.address.AddressHierarchyValidator.VerifiedAddress;
import com.str.backend.exception.AddressHierarchyException;
import com.str.backend.exception.AddressHierarchyException.Code;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lanac adrese (T4) nad stvarnim tablicama registra (H2): županija → općina → naselje → ulica →
 * kućni broj, s istim string ključevima kao kaskada {@code /api/address/**} ({@code zu_rb},
 * {@code jls_mb} s LPAD-om na 5, {@code na_mb}). {@code postanski_brojevi} nema entitet, pa ga test
 * stvara sam, kao {@link PostalCodeQueryTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class AddressHierarchyValidatorTest {

    private static final long ZAGREB_ZUP = 970001L;
    private static final long SPLIT_ZUP = 970002L;
    private static final long ZAGREB = 971001L;
    private static final long SPLIT = 971002L;
    /** Općina čiji {@code jls_mb} počinje nulom — naselje ga nosi kao broj (1234 → '01234'). */
    private static final long VODICE = 971003L;
    private static final long BREZOVICA = 972001L;
    private static final long SPLIT_NASELJE = 972002L;
    private static final long VODICE_NASELJE = 972003L;
    private static final long BREZOVICKA = 973001L;
    private static final long MARMONTOVA = 973002L;
    private static final long BREZOVICKA_1 = 974001L;
    private static final long MARMONTOVA_3 = 974002L;

    @Autowired private AddressHierarchyValidator validator;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rpj_dgu.postanski_brojevi (
                  id INTEGER PRIMARY KEY, broj_pu VARCHAR(10) NOT NULL, red_broj VARCHAR(10),
                  naziv_pu VARCHAR(100), naselje VARCHAR(100), zupanija VARCHAR(100))
                """);
        cleanUp();

        jdbc.update("INSERT INTO rpj_dgu.zupanije (id, zu_ime, zu_rb) VALUES (?, 'Grad Zagreb', 971)", ZAGREB_ZUP);
        jdbc.update("INSERT INTO rpj_dgu.zupanije (id, zu_ime, zu_rb) VALUES (?, 'Splitsko-dalmatinska županija', 972)", SPLIT_ZUP);

        jdbc.update("INSERT INTO rpj_dgu.gradovi_i_opcine (id, jls_ime, jls_mb, zu_rb) VALUES (?, 'Zagreb', '97101', 971)", ZAGREB);
        jdbc.update("INSERT INTO rpj_dgu.gradovi_i_opcine (id, jls_ime, jls_mb, zu_rb) VALUES (?, 'Split', '97201', 972)", SPLIT);
        jdbc.update("INSERT INTO rpj_dgu.gradovi_i_opcine (id, jls_ime, jls_mb, zu_rb) VALUES (?, 'Vodice', '01234', 972)", VODICE);

        jdbc.update("INSERT INTO rpj_dgu.naselja (id, na_ime, na_mb, jls_mb) VALUES (?, 'Brezovica', '9710001', 97101)", BREZOVICA);
        jdbc.update("INSERT INTO rpj_dgu.naselja (id, na_ime, na_mb, jls_mb) VALUES (?, 'Split', '9720001', 97201)", SPLIT_NASELJE);
        jdbc.update("INSERT INTO rpj_dgu.naselja (id, na_ime, na_mb, jls_mb) VALUES (?, 'Vodice', '0123401', 1234)", VODICE_NASELJE);

        jdbc.update("INSERT INTO eturizam_test.ar_ulice (id, naziv_ulice, naselje_id) VALUES (?, 'Brezovička cesta', '9710001')", BREZOVICKA);
        jdbc.update("INSERT INTO eturizam_test.ar_ulice (id, naziv_ulice, naselje_id) VALUES (?, 'Marmontova', '9720001')", MARMONTOVA);
        jdbc.update("INSERT INTO eturizam_test.ar_address (id, broj, ulica_id) VALUES (?, '1', ?)", BREZOVICKA_1, BREZOVICKA);
        jdbc.update("INSERT INTO eturizam_test.ar_address (id, broj, ulica_id) VALUES (?, '3', ?)", MARMONTOVA_3, MARMONTOVA);

        // Brezovica: isto ime u drugoj županiji — taj broj ne pripada zagrebačkoj Brezovici
        postal(975001, "10257", "Brezovica", "Grad Zagreb");
        postal(975002, "31542", "Brezovica", "Osječko-baranjska županija");
        postal(975003, "21000", "Split", "Splitsko-dalmatinska županija");
        // Vodice: registar naselju ne zna poštanski broj
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM eturizam_test.ar_address WHERE id BETWEEN 974000 AND 974999");
        jdbc.update("DELETE FROM eturizam_test.ar_ulice WHERE id BETWEEN 973000 AND 973999");
        jdbc.update("DELETE FROM rpj_dgu.postanski_brojevi WHERE id BETWEEN 975000 AND 975999");
        jdbc.update("DELETE FROM rpj_dgu.naselja WHERE id BETWEEN 972000 AND 972999");
        jdbc.update("DELETE FROM rpj_dgu.gradovi_i_opcine WHERE id BETWEEN 971000 AND 971999");
        jdbc.update("DELETE FROM rpj_dgu.zupanije WHERE id BETWEEN 970000 AND 970999");
    }

    // --- adresa podnositelja ---

    /** Odabrano iz registra: nazivi su iz registra, poslani tekst se zanemaruje. */
    @Test
    void subject_consistentChainFromRegistry_returnsRegistryNames() {
        VerifiedAddress v = validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, BREZOVICKA, BREZOVICKA_1, "Podmetnuta", "99", "10257"));

        assertThat(v).isEqualTo(new VerifiedAddress(
                "Grad Zagreb", "Zagreb", "Brezovica", "Brezovička cesta", "1", "10257"));
    }

    /** Ulica i kućni broj koje registar nema upisuju se tekstom; dopušten je i „bb". */
    @Test
    void subject_freeTextStreetAndHouseNumber_accepted() {
        VerifiedAddress v = validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, null, null, " Nova ulica ", "bb", "10257"));

        assertThat(v.street()).isEqualTo("Nova ulica");
        assertThat(v.houseNumber()).isEqualTo("bb");
        assertThat(v.settlement()).isEqualTo("Brezovica");
    }

    /** Ulica iz registra, kućni broj upisan (registar ga nema). */
    @Test
    void subject_registryStreetWithFreeTextHouseNumber_accepted() {
        VerifiedAddress v = validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, BREZOVICKA, null, null, "5a", "10257"));

        assertThat(v.street()).isEqualTo("Brezovička cesta");
        assertThat(v.houseNumber()).isEqualTo("5a");
    }

    /** Stari klijent ili nacrt bez id-eva: nema tihog prihvaćanja slobodnog teksta za općinu i naselje. */
    @Test
    void subject_missingLevels_areIncomplete() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                null, ZAGREB, BREZOVICA, null, null, "Ulica", "1", "10257")),
                Code.ADDRESS_INCOMPLETE, "podnositelj.zupanijaId");
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, null, null, null, null, "Ulica", "1", "10257")),
                Code.ADDRESS_INCOMPLETE, "podnositelj.opcinaId");
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, null, null, null, "Ulica", "1", "10257")),
                Code.ADDRESS_INCOMPLETE, "podnositelj.naseljeId");
    }

    @Test
    void subject_freeTextRequired_whenNoRegistryId() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, null, null, "  ", "1", "10257")),
                Code.ADDRESS_INCOMPLETE, "podnositelj.ulica");
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, BREZOVICKA, null, null, null, "10257")),
                Code.ADDRESS_INCOMPLETE, "podnositelj.kucniBroj");
    }

    @Test
    void subject_municipalityOfAnotherCounty_isInconsistent() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, SPLIT, SPLIT_NASELJE, null, null, "Ulica", "1", "21000")),
                Code.ADDRESS_INCONSISTENT, "podnositelj.opcinaId");
    }

    @Test
    void subject_settlementOfAnotherMunicipality_isInconsistent() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, SPLIT_NASELJE, null, null, "Ulica", "1", "21000")),
                Code.ADDRESS_INCONSISTENT, "podnositelj.naseljeId");
    }

    @Test
    void subject_streetOfAnotherSettlement_isInconsistent() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, MARMONTOVA, null, null, "1", "10257")),
                Code.ADDRESS_INCONSISTENT, "podnositelj.ulicaId");
    }

    @Test
    void subject_houseNumberOfAnotherStreet_isInconsistent() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, BREZOVICKA, MARMONTOVA_3, null, null, "10257")),
                Code.ADDRESS_INCONSISTENT, "podnositelj.kucniBrojId");
    }

    /** Kućni broj iz registra visi o ulici iz registra. */
    @Test
    void subject_houseNumberWithoutStreetId_isIncomplete() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, null, BREZOVICKA_1, "Brezovička cesta", null, "10257")),
                Code.ADDRESS_INCOMPLETE, "podnositelj.ulicaId");
    }

    @Test
    void subject_unknownCounty_isInconsistent() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                970999L, ZAGREB, BREZOVICA, null, null, "Ulica", "1", "10257")),
                Code.ADDRESS_INCONSISTENT, "podnositelj.zupanijaId");
    }

    /** Broj istoimenog naselja iz druge županije nije broj ovog naselja. */
    @Test
    void subject_postalCodeOfAnotherSettlement_isInconsistent() {
        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, null, null, "Ulica", "1", "31542")),
                Code.ADDRESS_INCONSISTENT, "podnositelj.postanskiBroj");
    }

    @Test
    void subject_missingPostalCode_filledWhenUnambiguous() {
        VerifiedAddress v = validator.verifySubjectAddress(new AddressSelection(
                ZAGREB_ZUP, ZAGREB, BREZOVICA, null, null, "Ulica", "1", null));

        assertThat(v.postalCode()).isEqualTo("10257");
    }

    /** Naselju bez broja u registru (≈1,4 %) vrijedi upisani broj; bez njega adresa nije potpuna. */
    @Test
    void subject_settlementWithoutPostalCodes_acceptsTypedCode() {
        VerifiedAddress v = validator.verifySubjectAddress(new AddressSelection(
                SPLIT_ZUP, VODICE, VODICE_NASELJE, null, null, "Obala", "2", "22211"));

        assertThat(v.postalCode()).isEqualTo("22211");
        // LPAD: naselje.jls_mb 1234 pripada općini '01234'
        assertThat(v.municipality()).isEqualTo("Vodice");

        expect(() -> validator.verifySubjectAddress(new AddressSelection(
                SPLIT_ZUP, VODICE, VODICE_NASELJE, null, null, "Obala", "2", null)),
                Code.ADDRESS_INCOMPLETE, "podnositelj.postanskiBroj");
    }

    // --- adresa objekta ---

    @Test
    void facility_consistentChain_passes() {
        assertThatCode(() -> validator.verifyFacilityAddress(
                ZAGREB_ZUP, String.valueOf(ZAGREB), String.valueOf(BREZOVICA), BREZOVICKA_1, false))
                .doesNotThrowAnyException();
    }

    /** Objekt iz eTurizma adresu nosi iz str.* — id-eve ne mora imati. */
    @Test
    void facility_existingWithoutIds_passes() {
        assertThatCode(() -> validator.verifyFacilityAddress(null, null, null, null, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.verifyFacilityAddress(ZAGREB_ZUP, null, "  ", null, true))
                .doesNotThrowAnyException();
    }

    @Test
    void facility_municipalityOfAnotherCounty_isInconsistent() {
        expect(() -> validator.verifyFacilityAddress(ZAGREB_ZUP, String.valueOf(SPLIT), null, null, false),
                Code.ADDRESS_INCONSISTENT, "cityId");
    }

    @Test
    void facility_settlementOfAnotherMunicipality_isInconsistent() {
        expect(() -> validator.verifyFacilityAddress(
                        ZAGREB_ZUP, String.valueOf(ZAGREB), String.valueOf(SPLIT_NASELJE), null, false),
                Code.ADDRESS_INCONSISTENT, "settlementId");
    }

    @Test
    void facility_houseNumberOfAnotherSettlement_isInconsistent() {
        expect(() -> validator.verifyFacilityAddress(
                        ZAGREB_ZUP, String.valueOf(ZAGREB), String.valueOf(BREZOVICA), MARMONTOVA_3, false),
                Code.ADDRESS_INCONSISTENT, "kucniBrojId");
    }

    /** Bez naselja kućni broj mora barem ležati u poslanoj općini. */
    @Test
    void facility_houseNumberWithoutSettlement_checkedAgainstMunicipality() {
        assertThatCode(() -> validator.verifyFacilityAddress(
                ZAGREB_ZUP, String.valueOf(ZAGREB), null, BREZOVICKA_1, false))
                .doesNotThrowAnyException();
        expect(() -> validator.verifyFacilityAddress(ZAGREB_ZUP, String.valueOf(ZAGREB), null, MARMONTOVA_3, false),
                Code.ADDRESS_INCONSISTENT, "kucniBrojId");
    }

    /** Poslani id-evi i za objekt iz eTurizma moraju činiti lanac. */
    @Test
    void facility_existingWithInconsistentIds_isInconsistent() {
        expect(() -> validator.verifyFacilityAddress(ZAGREB_ZUP, String.valueOf(SPLIT), null, null, true),
                Code.ADDRESS_INCONSISTENT, "cityId");
    }

    /** Novi objekt: općina mora biti id iz registra; postojeći zadržava dosadašnju toleranciju. */
    @Test
    void facility_nonNumericOrUnknownMunicipality() {
        expect(() -> validator.verifyFacilityAddress(ZAGREB_ZUP, "Zagreb", null, null, false),
                Code.ADDRESS_INCONSISTENT, "cityId");
        expect(() -> validator.verifyFacilityAddress(ZAGREB_ZUP, "971999", null, null, false),
                Code.ADDRESS_INCONSISTENT, "cityId");
        assertThatCode(() -> validator.verifyFacilityAddress(ZAGREB_ZUP, "Zagreb", null, null, true))
                .doesNotThrowAnyException();
    }

    /** Nepoznat kućni broj ovdje se preskače — prijavljuje ga CadastreResolver svojom porukom. */
    @Test
    void facility_unknownHouseNumber_leftToCadastreResolver() {
        assertThatCode(() -> validator.verifyFacilityAddress(
                ZAGREB_ZUP, String.valueOf(ZAGREB), String.valueOf(BREZOVICA), 974999L, false))
                .doesNotThrowAnyException();
    }

    private static void expect(ThrowingCallable call, Code code, String field) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(AddressHierarchyException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(code);
                    assertThat(e.getField()).isEqualTo(field);
                });
    }

    private void postal(int id, String code, String naselje, String zupanija) {
        jdbc.update("INSERT INTO rpj_dgu.postanski_brojevi (id, broj_pu, naselje, zupanija) VALUES (?, ?, ?, ?)",
                id, code, naselje, zupanija);
    }
}
