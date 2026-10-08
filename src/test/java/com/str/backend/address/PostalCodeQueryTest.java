package com.str.backend.address;

import com.str.backend.address.SettlementRepository.SettlementProjection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Native upiti poštanskih brojeva na pravoj bazi (H2): popis naselja općine
 * ({@code /api/address/settlements}) i poštanski broj adrese podnositelja.
 *
 * <p>{@code rpj_dgu.postanski_brojevi} nema entitet, pa ga test stvara sam, po changesetu 105.
 * Podaci prate lokalni seed (changeset 106): {@code zupanija} je pisana kao {@code zu_ime}.
 * Varijante formata (velika slova, bez sufiksa, „Zagreb" umjesto „Grad Zagreb") pokriva
 * {@link PostalCodesTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class PostalCodeQueryTest {

    private static final long ZAGREB_OPCINA = 991001L;
    private static final long SPLIT_OPCINA = 991002L;
    private static final long BREZOVICA = 992001L;
    private static final long LUCKO = 992002L;
    private static final long SPLIT = 992003L;
    private static final long ADRESA_BREZOVICA = 994001L;
    private static final long ADRESA_LUCKO = 994002L;
    private static final long ADRESA_SPLIT = 994003L;

    @Autowired private SettlementRepository settlementRepository;
    @Autowired private HouseNumberRepository houseNumberRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rpj_dgu.postanski_brojevi (
                  id INTEGER PRIMARY KEY, broj_pu VARCHAR(10) NOT NULL, red_broj VARCHAR(10),
                  naziv_pu VARCHAR(100), naselje VARCHAR(100), zupanija VARCHAR(100))
                """);
        cleanUp();

        jdbc.update("INSERT INTO rpj_dgu.zupanije (id, zu_ime, zu_rb) VALUES (990021, 'Grad Zagreb', 921)");
        jdbc.update("INSERT INTO rpj_dgu.zupanije (id, zu_ime, zu_rb) VALUES (990017, 'Splitsko-dalmatinska županija', 917)");

        jdbc.update("INSERT INTO rpj_dgu.gradovi_i_opcine (id, jls_ime, jls_mb, zu_rb) VALUES (?, 'Zagreb', '92101', 921)", ZAGREB_OPCINA);
        jdbc.update("INSERT INTO rpj_dgu.gradovi_i_opcine (id, jls_ime, jls_mb, zu_rb) VALUES (?, 'Split', '91701', 917)", SPLIT_OPCINA);

        jdbc.update("INSERT INTO rpj_dgu.naselja (id, na_ime, na_mb, jls_mb) VALUES (?, 'Brezovica', '9210001', 92101)", BREZOVICA);
        jdbc.update("INSERT INTO rpj_dgu.naselja (id, na_ime, na_mb, jls_mb) VALUES (?, 'Lučko', '9210002', 92101)", LUCKO);
        jdbc.update("INSERT INTO rpj_dgu.naselja (id, na_ime, na_mb, jls_mb) VALUES (?, 'Split', '9170001', 91701)", SPLIT);

        // Brezovica: isto ime u tri županije — samo zagrebački broj pripada naselju
        postal(995001, "10257", "Brezovica", "Grad Zagreb");
        postal(995002, "31542", "Brezovica", "Osječko-baranjska županija");
        postal(995003, "33411", "Brezovica", "Virovitičko-podravska županija");
        // Lučko: nijedan redak ne odgovara županiji naselja → svi brojevi ostaju
        postal(995004, "10250", "Lučko", "Zagrebačka županija");
        postal(995005, "10251", "Lučko", "Sisačko-moslavačka županija");
        // Split: jedan broj, ponašanje nepromijenjeno
        postal(995006, "21000", "Split", "Splitsko-dalmatinska županija");

        jdbc.update("INSERT INTO eturizam_test.ar_ulice (id, naziv_ulice, naselje_id) VALUES (993001, 'Brezovička cesta', '9210001')");
        jdbc.update("INSERT INTO eturizam_test.ar_ulice (id, naziv_ulice, naselje_id) VALUES (993002, 'Lučko', '9210002')");
        jdbc.update("INSERT INTO eturizam_test.ar_ulice (id, naziv_ulice, naselje_id) VALUES (993003, 'Marmontova', '9170001')");
        jdbc.update("INSERT INTO eturizam_test.ar_address (id, broj, ulica_id) VALUES (?, '1', 993001)", ADRESA_BREZOVICA);
        jdbc.update("INSERT INTO eturizam_test.ar_address (id, broj, ulica_id) VALUES (?, '2', 993002)", ADRESA_LUCKO);
        jdbc.update("INSERT INTO eturizam_test.ar_address (id, broj, ulica_id) VALUES (?, '3', 993003)", ADRESA_SPLIT);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM eturizam_test.ar_address WHERE id BETWEEN 994000 AND 994999");
        jdbc.update("DELETE FROM eturizam_test.ar_ulice WHERE id BETWEEN 993000 AND 993999");
        jdbc.update("DELETE FROM rpj_dgu.postanski_brojevi WHERE id BETWEEN 995000 AND 995999");
        jdbc.update("DELETE FROM rpj_dgu.naselja WHERE id BETWEEN 992000 AND 992999");
        jdbc.update("DELETE FROM rpj_dgu.gradovi_i_opcine WHERE id BETWEEN 991000 AND 991999");
        jdbc.update("DELETE FROM rpj_dgu.zupanije WHERE id BETWEEN 990000 AND 990999");
    }

    // --- /api/address/settlements ---

    @Test
    void settlements_brezovicaInGradZagreb_keepsOnlyZagrebPostalCode() {
        List<SettlementProjection> rows = settlementRepository.findByMunicipalityIdOrderByName(ZAGREB_OPCINA, "Brezovica");

        assertThat(rows).extracting(SettlementProjection::getId).containsOnly(BREZOVICA);
        assertThat(rows).extracting(SettlementProjection::getPostalCode).containsExactly("10257");
    }

    @Test
    void settlements_noRowMatchesCounty_fallsBackToAllCodesByName() {
        List<SettlementProjection> rows = settlementRepository.findByMunicipalityIdOrderByName(ZAGREB_OPCINA, "Lučko");

        assertThat(rows).extracting(SettlementProjection::getPostalCode).containsExactly("10250", "10251");
    }

    @Test
    void settlements_singlePostalCode_unchanged() {
        List<SettlementProjection> rows = settlementRepository.findByMunicipalityIdOrderByName(SPLIT_OPCINA, null);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo(SPLIT);
            assertThat(r.getName()).isEqualTo("Split");
            assertThat(r.getPostalCode()).isEqualTo("21000");
        });
    }

    @Test
    void settlements_wholeMunicipality_ordersByNameAndFiltersPerSettlement() {
        List<SettlementProjection> rows = settlementRepository.findByMunicipalityIdOrderByName(ZAGREB_OPCINA, null);

        assertThat(rows).extracting(SettlementProjection::getName, SettlementProjection::getPostalCode)
                .containsExactly(
                        tuple("Brezovica", "10257"),
                        tuple("Lučko", "10250"),
                        tuple("Lučko", "10251"));
    }

    // --- adresa podnositelja ---

    @Test
    void lessorAddress_includesMunicipality() {
        HouseNumberRepository.LessorAddressProjection a =
                houseNumberRepository.resolveFullAddress(ADRESA_BREZOVICA).orElseThrow();

        assertThat(a.getStreet()).isEqualTo("Brezovička cesta");
        assertThat(a.getStreetNumber()).isEqualTo("1");
        assertThat(a.getSettlement()).isEqualTo("Brezovica");
        assertThat(a.getMunicipality()).isEqualTo("Zagreb");
        assertThat(a.getCounty()).isEqualTo("Grad Zagreb");
    }

    @Test
    void lessorPostalCode_brezovicaInGradZagreb_isSingleCode() {
        assertThat(PostalCodes.single(houseNumberRepository.findPostalCandidates(ADRESA_BREZOVICA)))
                .isEqualTo("10257");
    }

    @Test
    void lessorPostalCode_multipleCodesAfterFallback_isNull() {
        assertThat(houseNumberRepository.findPostalCandidates(ADRESA_LUCKO)).hasSize(2);
        assertThat(PostalCodes.single(houseNumberRepository.findPostalCandidates(ADRESA_LUCKO))).isNull();
    }

    @Test
    void lessorPostalCode_singleCode() {
        assertThat(PostalCodes.single(houseNumberRepository.findPostalCandidates(ADRESA_SPLIT)))
                .isEqualTo("21000");
    }

    private void postal(int id, String broj, String naselje, String zupanija) {
        jdbc.update("INSERT INTO rpj_dgu.postanski_brojevi (id, broj_pu, naziv_pu, naselje, zupanija) VALUES (?, ?, ?, ?, ?)",
                id, broj, naselje, naselje, zupanija);
    }
}
