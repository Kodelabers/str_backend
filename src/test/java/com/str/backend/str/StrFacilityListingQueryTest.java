package com.str.backend.str;

import com.str.backend.str.StrFacilityRepository.FacilityListingRow;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import com.str.backend.str.StrFacilityRepository.ListingTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native query popisa objekata na pravoj bazi (H2), jer su pravila aktualnosti ono što nosi
 * najveći rizik. Pravila su prepisana iz eTurizmova viewa {@code str.vw_src_facility_actual}
 * (v. komentar u {@link StrFacilityRepository}); ovdje je svako pokriveno zasebnim slučajem.
 *
 * <p>Tablice koje query joina nemaju entitete, pa ih Hibernate ne stvara — ovaj test ih kreira
 * sam i dopunjava {@code str.facility} kolonama koje {@link StrFacilityEntity} ne mapira (entitet
 * je namjerno minimalan i {@code @Immutable}). Struktura odgovara pravoj eTurizam shemi (str2),
 * provjerenoj na CDU testu.
 *
 * <p>Model: zapis {@code facility} je smještajna jedinica, {@code system_uuid} je objekt. Svaki
 * predmet ovdje ima točno jedan dokument s istim id-em kao predmet.
 */
@SpringBootTest
@ActiveProfiles("test")
class StrFacilityListingQueryTest {

    private static final String OIB = "06756460531";
    private static final String OTHER_OIB = "12312312316";
    private static final List<String> CODES = List.of("FS_SOBA", "FS_APARTMAN", "FS_KUCA_ZA_ODMOR");

    private static final long STATUS_IZVRSNO = 1050;
    private static final long STATUS_U_RJESAVANJU = 1051;
    private static final long VERIFIKACIJA_U_IZRADI = 1060;
    private static final long VERIFIKACIJA_ZAVRSENA = 1061;

    private static final String RJESENJE = "DST_R_OD_UG_DOM";
    private static final String ZAHTJEV = "DST_Z_PROMJ_POD";
    private static final String MIGRACIJA = "optimit";
    private static final String SLUZBENIK = "sluzbenik";

    @Autowired private StrFacilityRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUpSchemaAndData() {
        // str.facility postoji iz entiteta, ali samo s id/active/subject_version_id
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS name VARCHAR(255)");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS system_uuid VARCHAR(36)");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS document_id BIGINT");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS address_id BIGINT");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS category_id BIGINT");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS business_status_id BIGINT");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS same_address_subject BOOLEAN");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS registration_number VARCHAR(64)");
        // Kontakt objekta — čita se za predpopunu kontakt bloka u formi (stavka 11, 10.09.2026.).
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS email VARCHAR(255)");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS phone VARCHAR(50)");
        // Pravila aktualnosti iz eTurizmova viewa
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS created_by VARCHAR(255)");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS created_date TIMESTAMP");
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS historical BOOLEAN");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.codebook_element (
                  id BIGINT PRIMARY KEY, active BOOLEAN, code VARCHAR(100), name VARCHAR(255))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.document (
                  id BIGINT PRIMARY KEY, active BOOLEAN, business_case_id BIGINT)
                """);
        jdbc.execute("ALTER TABLE str.document ADD COLUMN IF NOT EXISTS subtype_code VARCHAR(64)");
        jdbc.execute("ALTER TABLE str.document ADD COLUMN IF NOT EXISTS execution_date TIMESTAMP");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.business_case (
                  id BIGINT PRIMARY KEY, active BOOLEAN, status_type_id BIGINT,
                  jurisdiction_organizational_unit_id BIGINT, subject_version_id BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.business_case_verification (
                  id BIGINT PRIMARY KEY, unverified_business_case_id BIGINT,
                  verified_business_case_id BIGINT, status_id BIGINT)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.sif_vrsta_dokumenata (code VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.sif_podvrsta_dokumenta (
                  code VARCHAR(64) PRIMARY KEY, vrsta_dokumenata_code VARCHAR(64))
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.organizational_unit (id BIGINT PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.facility_type (
                  id BIGINT PRIMARY KEY, active BOOLEAN, facility_id BIGINT,
                  type_id BIGINT, sub_type_id BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.facility_capacity (
                  id BIGINT PRIMARY KEY, active BOOLEAN, facility_id BIGINT,
                  type_id BIGINT, quantity INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.facility_unit (
                  id BIGINT PRIMARY KEY, active BOOLEAN, facility_id BIGINT,
                  type_id BIGINT, number_of_units INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.facility_unit_capacity (
                  id BIGINT PRIMARY KEY, active BOOLEAN, facility_unit_id BIGINT,
                  type_id BIGINT, quantity INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.facility_content (
                  id BIGINT PRIMARY KEY, active BOOLEAN, facility_id BIGINT,
                  type_id BIGINT, quantity INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.facility_content_capacity (
                  id BIGINT PRIMARY KEY, active BOOLEAN, facility_content_id BIGINT,
                  type_id BIGINT, quantity INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.address (
                  id BIGINT PRIMARY KEY, active BOOLEAN, county_id BIGINT, municipality_id BIGINT,
                  settlement_id BIGINT, street_id BIGINT, house_number_id BIGINT,
                  county VARCHAR(255), municipality VARCHAR(255), settlement VARCHAR(255),
                  street VARCHAR(255), house_number VARCHAR(32), postal_code VARCHAR(16),
                  full_address VARCHAR(500))
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.county (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.municipality (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.settlement (
                  id BIGINT PRIMARY KEY, name VARCHAR(255), postal_code VARCHAR(16))
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.street (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.house_number (id BIGINT PRIMARY KEY, name VARCHAR(32))");

        for (String table : List.of("facility", "facility_type", "facility_capacity", "facility_unit",
                "facility_unit_capacity", "facility_content", "facility_content_capacity", "document", "business_case", "business_case_verification",
                "sif_vrsta_dokumenata", "sif_podvrsta_dokumenta", "organizational_unit", "address",
                "county", "municipality", "settlement", "street", "house_number", "codebook_element",
                "subject_address", "subject_version", "subject")) {
            jdbc.execute("DELETE FROM str." + table);
        }

        jdbc.execute("""
                INSERT INTO str.codebook_element (id, active, code, name) VALUES
                  (1000, true, 'FT_UGOST_USL_U_DOM', 'Usluge u domacinstvu'),
                  (1001, true, 'FT_RESTORAN', 'Restorani'),
                  (1010, true, 'FS_SOBA', 'Soba'),
                  (1011, true, 'FS_APARTMAN', 'Apartman'),
                  (1014, true, 'FS_PIZZERIA', 'Pizzeria'),
                  (1020, true, 'C_3_ZVJEZDICE', 'Tri zvjezdice'),
                  (1030, true, 'FBS_ACTIVE', 'Aktivan'),
                  (1031, true, 'FBS_INACTIVE', 'Odjavljen'),
                  (1040, true, 'CAT_BROJ_KREVETA', 'Broj kreveta'),
                  (1041, true, 'CAT_BROJ_POM_KREVETA', 'Broj pomocnih kreveta'),
                  (1045, true, 'CT_DVO_SOBA', 'Dvokrevetna soba'),
                  (1046, true, 'CT_TRO_SOBA', 'Trokrevetna soba'),
                  (1047, true, 'CT_DJEC_IGR', 'Djecje igraliste'),
                  (1050, true, 'BCST_RJES_IZVRSNO', 'Rjesenje izvrsno'),
                  (1051, true, 'BCST_U_RJESAVANJU', 'U rjesavanju'),
                  (1060, true, 'BCVS_U_IZRADI', 'U izradi'),
                  (1061, true, 'BCVS_ZAVRSENA', 'Zavrsena')
                """);
        jdbc.execute("""
                INSERT INTO str.sif_vrsta_dokumenata (code) VALUES ('DOT_RJESENJE'), ('DOT_ZAHTJEV')
                """);
        jdbc.execute("""
                INSERT INTO str.sif_podvrsta_dokumenta (code, vrsta_dokumenata_code) VALUES
                  ('DST_R_OD_UG_DOM', 'DOT_RJESENJE'), ('DST_Z_PROMJ_POD', 'DOT_ZAHTJEV')
                """);
        jdbc.execute("INSERT INTO str.organizational_unit (id) VALUES (1)");
        jdbc.execute("""
                INSERT INTO str.subject (id, active, jips) VALUES
                  (1, true, '06756460531'), (2, true, '12312312316'), (3, false, '06756460531')
                """);
        jdbc.execute("""
                INSERT INTO str.subject_version (id, active, subject_id, historical, first_name, last_name) VALUES
                  (1, true, 1, false, 'Tonci', 'Beros'), (2, true, 2, false, 'Pero', 'Peric'),
                  (3, true, 3, false, 'Tonci', 'Beros')
                """);
        jdbc.execute("""
                INSERT INTO str.county (id, name) VALUES (91, 'Splitsko-dalmatinska zupanija')
                """);
        jdbc.execute("INSERT INTO str.municipality (id, name) VALUES (81, 'Makarska')");
        jdbc.execute("INSERT INTO str.settlement (id, name, postal_code) VALUES (71, 'Makarska', '21300')");
        jdbc.execute("INSERT INTO str.street (id, name) VALUES (61, 'Kraljevska')");
        jdbc.execute("INSERT INTO str.house_number (id, name) VALUES (51, '88'), (52, '4')");
        jdbc.execute("""
                INSERT INTO str.address (id, active, county_id, municipality_id, settlement_id,
                                         street_id, house_number_id, full_address) VALUES
                  (41, true, 91, 81, 71, 61, 51, 'Kraljevska 88, 21300 Makarska'),
                  (42, true, 91, 81, 71, 61, 52, 'Kraljevska 4, 21300 Makarska')
                """);
    }

    // -------------------------------------------------------------------------------------------
    // Prikaz i mapiranje
    // -------------------------------------------------------------------------------------------

    @Test
    void returnsOwnUnit_withTypeAddressCapacityAndObject() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba 1", SLUZBENIK, "2024-01-01 10:00:00");
        capacity(100, 10, 1040, 2);
        capacity(101, 10, 1041, 1);

        List<FacilityListingRow> rows = list(OIB);

        assertThat(rows).hasSize(1);
        FacilityListingRow row = rows.getFirst();
        assertThat(row.getFacilityId()).isEqualTo(10L);
        assertThat(row.getSystemUuid()).isEqualTo("uuid-10");
        assertThat(row.getVerified()).isTrue();
        assertThat(row.getTotalObjects()).isEqualTo(1);
        assertThat(row.getTotalUnits()).isEqualTo(1);
        assertThat(row.getName()).isEqualTo("Soba 1");
        assertThat(row.getSubtypeCode()).isEqualTo("FS_SOBA");
        assertThat(row.getSubtypeName()).isEqualTo("Soba");
        assertThat(row.getCategoryName()).isEqualTo("Tri zvjezdice");
        assertThat(row.getStatusName()).isEqualTo("Aktivan");
        assertThat(row.getBeds()).isEqualTo(2);
        assertThat(row.getAuxiliaryBeds()).isEqualTo(1);
        assertThat(row.getCountyName()).isEqualTo("Splitsko-dalmatinska zupanija");
        assertThat(row.getMunicipalityName()).isEqualTo("Makarska");
        assertThat(row.getSettlementName()).isEqualTo("Makarska");
        assertThat(row.getStreetName()).isEqualTo("Kraljevska");
        assertThat(row.getHouseNumber()).isEqualTo("88");
        assertThat(row.getPostalCode()).isEqualTo("21300");
        assertThat(row.getFullAddress()).isEqualTo("Kraljevska 88, 21300 Makarska");
    }

    /**
     * Migriran objekt (stari sustav, siječanj 2023.): predmet nema ni status ni datum izvršnosti,
     * a ipak se prikazuje — kao neverificiran.
     */
    @Test
    void listsMigratedUnit_asUnverified() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba 1", MIGRACIJA, "2023-01-19 13:00:00");

        List<FacilityListingRow> rows = list(OIB);

        assertThat(ids(rows)).containsExactly(10L);
        assertThat(rows.getFirst().getVerified()).isFalse();
    }

    /** Verificirani objekti idu prije neverificiranih, neovisno o id-u. */
    @Test
    void ordersVerifiedObjectsBeforeUnverified() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-migr", "Migrirana soba", MIGRACIJA, "2023-01-19 13:00:00");
        verifiedCase(901, 1);
        unit(20, 901, "uuid-verif", "Verificirana soba", SLUZBENIK, "2024-01-01 10:00:00");

        assertThat(ids(list(OIB))).containsExactly(20L, 10L);
    }

    /**
     * Objekt s više jedinica u istom predmetu: prikazuju se sve jedinice (svaka dobiva svoj RB), a
     * stranica broji objekte — objekt se ne lomi preko dviju stranica.
     */
    @Test
    void listsEveryUnitOfObject_andPaginatesByObject() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-vila", "Vila", MIGRACIJA, "2023-01-19 13:00:00.001");
        unit(11, 900, "uuid-vila", "Vila", MIGRACIJA, "2023-01-19 13:00:00.002");
        unit(12, 900, "uuid-vila", "Vila", MIGRACIJA, "2023-01-19 13:00:00.003");
        migratedCase(901, 1);
        unit(20, 901, "uuid-kuca", "Kuca", MIGRACIJA, "2023-01-19 14:00:00");

        List<FacilityListingRow> first = repository.findListingByOib(OIB, CODES, 1, 0);
        List<FacilityListingRow> second = repository.findListingByOib(OIB, CODES, 1, 1);

        assertThat(ids(first)).containsExactly(10L, 11L, 12L);
        assertThat(first).extracting(FacilityListingRow::getSystemUuid).containsOnly("uuid-vila");
        assertThat(first.getFirst().getTotalObjects()).isEqualTo(2);
        assertThat(first.getFirst().getTotalUnits()).isEqualTo(4);
        assertThat(ids(second)).containsExactly(20L);
        assertThat(repository.findListingByOib(OIB, CODES, 1, 2)).isEmpty();
    }

    @Test
    void countsObjectsAndUnits() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-vila", "Vila", MIGRACIJA, "2023-01-19 13:00:00.001");
        unit(11, 900, "uuid-vila", "Vila", MIGRACIJA, "2023-01-19 13:00:00.002");
        verifiedCase(901, 1);
        unit(20, 901, "uuid-kuca", "Kuca", SLUZBENIK, "2024-01-01 10:00:00");

        ListingTotals totals = repository.countListingByOib(OIB, CODES);

        assertThat(totals.getObjects()).isEqualTo(2);
        assertThat(totals.getUnits()).isEqualTo(3);
    }

    @Test
    void countsZero_whenLessorHasNothing() {
        ListingTotals totals = repository.countListingByOib(OIB, CODES);

        assertThat(totals.getObjects()).isZero();
        assertThat(totals.getUnits()).isZero();
    }

    // -------------------------------------------------------------------------------------------
    // Vlasnik
    // -------------------------------------------------------------------------------------------

    @Test
    void excludesUnitsOfOtherLessors() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Moja soba", SLUZBENIK, "2024-01-01 10:00:00");
        verifiedCase(901, 2);
        unit(11, 901, "uuid-11", "Tuda soba", SLUZBENIK, "2024-01-01 10:00:00");

        assertThat(ids(list(OIB))).containsExactly(10L);
    }

    /**
     * Vlasnik je subjekt predmeta, kao u eTurizmovu viewu — na CDU 43 aktualna zapisa nemaju
     * {@code facility.subject_version_id}, pa preko objekta ne bi bili ničiji.
     */
    @Test
    void ownerComesFromBusinessCase_evenWithoutFacilitySubjectVersion() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba 1", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("UPDATE str.facility SET subject_version_id = NULL WHERE id = 10");

        assertThat(ids(list(OIB))).containsExactly(10L);
        assertThat(repository.findOwnership(10L).orElseThrow().getOib()).isEqualTo(OIB);
    }

    /** Predaja rješenja uz RB pita jedinicu je li migrirana — po autoru zapisa, kao popis. */
    @Test
    void ownershipCarriesVerifiedFlag() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Verificirana soba", SLUZBENIK, "2024-01-01 10:00:00");
        migratedCase(901, 1);
        unit(11, 901, "uuid-11", "Migrirana soba", MIGRACIJA, "2023-01-19 13:00:00");

        assertThat(repository.findOwnership(10L).orElseThrow().getVerified()).isTrue();
        assertThat(repository.findOwnership(11L).orElseThrow().getVerified()).isFalse();
    }

    /**
     * Zapis subjekta se s vremenom nadjača novijim, pa stari ostane {@code active = false}.
     * Predmet vodi na verziju tog starog zapisa, a OIB je isti — mora se i dalje prikazati.
     */
    @Test
    void includesUnitsOfSupersededSubjectRow() {
        verifiedCase(900, 3); // subject_version 3 → subject 3 (active = false)
        unit(18, 900, "uuid-18", "Soba na starom subjektu", SLUZBENIK, "2024-01-01 10:00:00");

        assertThat(ids(list(OIB))).containsExactly(18L);
        assertThat(repository.findOwnership(18L).orElseThrow().getOib()).isEqualTo(OIB);
    }

    /** Adresa subjekta ({@code same_address_subject}) čita se preko subjekta predmeta. */
    @Test
    void readsSubjectAddress_viaBusinessCaseSubject() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba 1", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("UPDATE str.facility SET same_address_subject = true, subject_version_id = NULL,"
                + " address_id = NULL WHERE id = 10");
        jdbc.update("INSERT INTO str.subject_address (id, active, subject_version_id, address_id)"
                + " VALUES (1, true, 1, 42)");

        assertThat(list(OIB).getFirst().getHouseNumber()).isEqualTo("4");
        assertThat(repository.findOwnership(10L).orElseThrow().getHouseNumber()).isEqualTo("4");
    }

    // -------------------------------------------------------------------------------------------
    // Najnoviji predmet po objektu
    // -------------------------------------------------------------------------------------------

    /** Rješenje pa promjena podataka: oba su aktualna po viewu, vrijedi samo noviji predmet. */
    @Test
    void newestCaseWins_forVersionsOfSameObject() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-obj", "Stara verzija", SLUZBENIK, "2023-03-01 10:00:00");
        verifiedCase(901, 1);
        unit(11, 901, "uuid-obj", "Nova verzija", SLUZBENIK, "2024-03-01 10:00:00");

        List<FacilityListingRow> rows = list(OIB);

        assertThat(ids(rows)).containsExactly(11L);
        assertThat(repository.findOwnership(10L).orElseThrow().getCurrent()).isFalse();
        assertThat(repository.findOwnership(11L).orElseThrow().getCurrent()).isTrue();
    }

    /**
     * Migracija je isti objekt upisala u više predmeta, a verifikacija ugasi samo jedan. Noviji,
     * verificirani predmet skriva sve migrirane kopije — objekt se ne prikazuje dvaput.
     */
    @Test
    void verifiedCaseHidesMigratedCopiesOfSameObject() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-obj", "Kopija 1", MIGRACIJA, "2023-01-11 20:00:00");
        migratedCase(901, 1);
        unit(11, 901, "uuid-obj", "Kopija 2", MIGRACIJA, "2023-01-12 16:00:00");
        verifiedCase(902, 1);
        unit(12, 902, "uuid-obj", "Verificirana", SLUZBENIK, "2025-10-10 15:00:00");

        List<FacilityListingRow> rows = list(OIB);

        assertThat(ids(rows)).containsExactly(12L);
        assertThat(rows.getFirst().getVerified()).isTrue();
        assertThat(repository.findOwnership(10L).orElseThrow().getCurrent()).isFalse();
    }

    /**
     * Predmet čiji zapisi nemaju {@code created_date} nije „najnoviji" — Postgres bi bez
     * {@code NULLS LAST} kod {@code DESC} stavio NULL na prvo mjesto i sakrio stvarno noviji predmet.
     */
    @Test
    void caseWithoutCreatedDate_doesNotWinOverDatedCase() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-obj", "Bez datuma", SLUZBENIK, null);
        verifiedCase(901, 1);
        unit(11, 901, "uuid-obj", "S datumom", SLUZBENIK, "2024-03-01 10:00:00");

        // H2 inače NULL drži manjim (kod DESC zadnji); HIGH = ponašanje Postgresa, gdje je greška
        jdbc.execute("SET DEFAULT_NULL_ORDERING HIGH");
        try {
            assertThat(ids(list(OIB))).containsExactly(11L);
        } finally {
            jdbc.execute("SET DEFAULT_NULL_ORDERING LOW");
        }
    }

    /** Sve jedinice najnovijeg predmeta ostaju — rang je po predmetu, ne po zapisu. */
    @Test
    void newestCaseKeepsAllItsUnits() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-obj", "Stara soba", SLUZBENIK, "2023-03-01 10:00:00");
        verifiedCase(901, 1);
        unit(11, 901, "uuid-obj", "Soba A", SLUZBENIK, "2024-03-01 10:00:00.000");
        unit(12, 901, "uuid-obj", "Soba B", SLUZBENIK, "2024-03-01 10:00:00.013");

        assertThat(ids(list(OIB))).containsExactly(11L, 12L);
    }

    /**
     * Objekt prenesen na drugog vlasnika: noviji predmet istog objekta pripada drugom subjektu,
     * pa stari vlasnik objekt više ne vidi.
     */
    @Test
    void hidesObjectTransferredToAnotherLessor() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-transfer", "Prodana soba", SLUZBENIK, "2023-03-01 10:00:00");
        verifiedCase(901, 2);
        unit(20, 901, "uuid-transfer", "Ista soba, novi vlasnik", SLUZBENIK, "2024-03-01 10:00:00");

        assertThat(list(OIB)).isEmpty();
        assertThat(repository.countListingByOib(OIB, CODES).getObjects()).isZero();
        assertThat(ids(list(OTHER_OIB))).containsExactly(20L);
    }

    /** Noviji predmet s odjavljenim objektom ne smije pustiti da stariji aktivni „oživi". */
    @Test
    void newerDeregisteredCase_doesNotReviveOlderActiveOne() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-obj", "Aktivna stara", SLUZBENIK, "2023-03-01 10:00:00");
        verifiedCase(901, 1);
        unit(11, 901, "uuid-obj", "Odjavljena nova", SLUZBENIK, "2024-03-01 10:00:00");
        businessStatus(11, 1031L);

        assertThat(list(OIB)).isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // Uvjeti eTurizmova viewa
    // -------------------------------------------------------------------------------------------

    @Test
    void excludesHistoricalRow() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Povijesna", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("UPDATE str.facility SET historical = true WHERE id = 10");

        assertThat(list(OIB)).isEmpty();
    }

    @Test
    void excludesInactiveRow() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Neaktivna", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("UPDATE str.facility SET active = false WHERE id = 10");

        assertThat(list(OIB)).isEmpty();
    }

    /** Verificiran predmet čije rješenje još nije izvršno (u rješavanju) nije aktualan. */
    @Test
    void excludesVerifiedCaseThatIsNotExecutable() {
        businessCase(900, 1, STATUS_U_RJESAVANJU);
        document(900, RJESENJE, "2024-01-01 00:00:00");
        unit(10, 900, "uuid-10", "U rjesavanju", SLUZBENIK, "2024-01-01 10:00:00");

        assertThat(list(OIB)).isEmpty();
    }

    @Test
    void excludesVerifiedCaseWithoutOrFutureExecutionDate() {
        businessCase(900, 1, STATUS_IZVRSNO);
        document(900, RJESENJE, null);
        unit(10, 900, "uuid-10", "Bez datuma", SLUZBENIK, "2024-01-01 10:00:00");
        businessCase(901, 1, STATUS_IZVRSNO);
        document(901, RJESENJE, "2999-01-01 00:00:00");
        unit(11, 901, "uuid-11", "Buduci datum", SLUZBENIK, "2024-01-01 10:00:00");

        assertThat(list(OIB)).isEmpty();
    }

    /** Zapis vezan uz zahtjev (npr. za promjenu podataka), a ne uz rješenje, nije objekt. */
    @Test
    void excludesRowOnRequestDocument() {
        businessCase(900, 1, STATUS_IZVRSNO);
        document(900, ZAHTJEV, "2024-01-01 00:00:00");
        unit(10, 900, "uuid-10", "Zahtjev", SLUZBENIK, "2024-01-01 10:00:00");

        assertThat(list(OIB)).isEmpty();
    }

    @Test
    void excludesInactiveDocumentOrBusinessCase() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Neaktivan dokument", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("UPDATE str.document SET active = false WHERE id = 900");
        verifiedCase(901, 1);
        unit(11, 901, "uuid-11", "Neaktivan predmet", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("UPDATE str.business_case SET active = false WHERE id = 901");

        assertThat(list(OIB)).isEmpty();
    }

    @Test
    void excludesCaseWithoutOrganizationalUnit() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Bez org. jedinice", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("UPDATE str.business_case SET jurisdiction_organizational_unit_id = 99 WHERE id = 900");

        assertThat(list(OIB)).isEmpty();
    }

    /** Kao u viewu: zapis bez {@code created_by} nije ni verificiran ni migriran. */
    @Test
    void excludesRowWithoutCreatedBy() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Bez autora", null, "2024-01-01 10:00:00");

        assertThat(list(OIB)).isEmpty();
    }

    /** Bez {@code system_uuid} nema objekta (view ga izbaci kroz {@code HAVING count = 1}). */
    @Test
    void excludesRowWithoutSystemUuid() {
        verifiedCase(900, 1);
        unit(10, 900, null, "Bez uuid-a", SLUZBENIK, "2024-01-01 10:00:00");

        assertThat(list(OIB)).isEmpty();
    }

    /** Migrirani objekt u verifikaciji (izvor u izradi) i dalje se prikazuje kao neverificiran. */
    @Test
    void keepsMigratedUnitWhileVerificationIsInProgress() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-10", "Migrirana", MIGRACIJA, "2023-01-19 13:00:00");
        verifiedCase(901, 1);
        unit(11, 901, "uuid-10", "U verifikaciji", SLUZBENIK, "2025-10-10 15:00:00");
        verification(1, 900L, 901L, VERIFIKACIJA_U_IZRADI);

        List<FacilityListingRow> rows = list(OIB);

        // novi predmet je cilj verifikacije koja nije završena → nije aktualan, ne skriva stari
        assertThat(ids(rows)).containsExactly(10L);
        assertThat(rows.getFirst().getVerified()).isFalse();
    }

    /**
     * Izvor završene verifikacije s predmetom koji je ostao aktivan: verificirana verzija istog
     * objekta je novija, pa je rang predmeta prikazuje umjesto migrirane.
     */
    @Test
    void excludesSourceOfCompletedVerification() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-10", "Migrirana", MIGRACIJA, "2023-01-19 13:00:00");
        verifiedCase(901, 1);
        unit(11, 901, "uuid-10", "Verificirana", SLUZBENIK, "2025-10-10 15:00:00");
        verification(1, 900L, 901L, VERIFIKACIJA_ZAVRSENA);

        assertThat(ids(list(OIB))).containsExactly(11L);
    }

    /**
     * Simonovo pravilo za migrirane gleda samo cilj verifikacije, ne izvor: migrirani zapis čiji
     * je predmet ostao aktivan prikazuje se i kad je njegova verifikacija završena, ako nova
     * verzija nije isti objekt (drugi system_uuid).
     */
    @Test
    void keepsMigratedSourceOfCompletedVerification_whenNewVersionIsAnotherObject() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-10", "Migrirana", MIGRACIJA, "2023-01-19 13:00:00");
        verifiedCase(901, 1);
        unit(11, 901, "uuid-11", "Verificirana", SLUZBENIK, "2025-10-10 15:00:00");
        verification(1, 900L, 901L, VERIFIKACIJA_ZAVRSENA);

        List<FacilityListingRow> rows = list(OIB);

        assertThat(ids(rows)).containsExactly(11L, 10L);
        assertThat(rows.get(1).getVerified()).isFalse();
    }

    /**
     * Novi objekt u verifikaciji koja traje (CDU: „Pero 1A”) nije neverificiran — neverificiran
     * je samo migrirani (optimit), odluka eTurizma 7. 10. 2026. Ne prikazuje se i ne prolazi claim.
     */
    @Test
    void excludesUnitsInOngoingVerification() {
        businessCase(900, 1, STATUS_U_RJESAVANJU);
        document(900, RJESENJE, "2026-05-19 00:00:00");
        unit(10, 900, "uuid-10", "Pero 1A", SLUZBENIK, "2026-10-07 10:56:00");
        verification(1, null, 900L, VERIFIKACIJA_U_IZRADI);

        assertThat(list(OIB)).isEmpty();
        assertThat(repository.findOwnership(10L).orElseThrow().getCurrent()).isFalse();
    }

    /** Kao u viewu ({@code HAVING count(system_uuid) = 1}): više redaka verifikacije izbaci zapis. */
    @Test
    void excludesRowWithSeveralVerificationRows() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-10", "Dvije verifikacije", MIGRACIJA, "2023-01-19 13:00:00");
        verification(1, 900L, null, VERIFIKACIJA_U_IZRADI);
        verification(2, 900L, null, VERIFIKACIJA_U_IZRADI);

        assertThat(list(OIB)).isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // Naši filtri: poslovni status (W-5) i vrsta smještaja
    // -------------------------------------------------------------------------------------------

    /**
     * Zapis je aktivan, ali objekt je odjavljen. {@code facility.active} je zastavica verzije
     * zapisa, pa je na CDU takvih gotovo polovica — ne smiju na popis.
     */
    @Test
    void excludesDeregisteredUnit_evenWhenRowIsActive() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Aktivna soba", SLUZBENIK, "2024-01-01 10:00:00");
        verifiedCase(901, 1);
        unit(11, 901, "uuid-11", "Odjavljena soba", SLUZBENIK, "2024-01-01 10:00:00");
        businessStatus(11, 1031L);

        assertThat(ids(list(OIB))).containsExactly(10L);
        assertThat(repository.countListingByOib(OIB, CODES).getObjects()).isEqualTo(1);
    }

    /** Bez poslovnog statusa se ne zna da objekt posluje, pa se ne prikazuje. */
    @Test
    void excludesUnitWithoutBusinessStatus() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba bez statusa", SLUZBENIK, "2024-01-01 10:00:00");
        businessStatus(10, null);

        assertThat(list(OIB)).isEmpty();
        assertThat(repository.countListingByOib(OIB, CODES).getObjects()).isZero();
    }

    /**
     * {@code facility_type.active} u eTurizmu smije biti NULL — njihov vlastiti view ga ne filtrira.
     * Uz {@code active = true} objekt bi ostao bez vrste i ispao s popisa u cijelosti.
     */
    @Test
    void resolvesType_whenFacilityTypeActiveIsNull() {
        verifiedCase(900, 1);
        unitWithoutType(10, 900, "uuid-10", "Soba 1", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("INSERT INTO str.facility_type (id, active, facility_id, type_id, sub_type_id)"
                + " VALUES (100, NULL, 10, 1000, 1010)");

        List<FacilityListingRow> rows = list(OIB);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSubtypeCode()).isEqualTo("FS_SOBA");
    }

    /** Iznajmljivač u eTurizmu može imati i restoran — na dashboard smještaja ne ide. */
    @Test
    void excludesNonAccommodationSubtypes() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba 1", SLUZBENIK, "2024-01-01 10:00:00");
        verifiedCase(901, 1);
        unitWithoutType(15, 901, "uuid-15", "Pizzeria", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("INSERT INTO str.facility_type (id, active, facility_id, type_id, sub_type_id)"
                + " VALUES (150, true, 15, 1001, 1014)");

        assertThat(ids(list(OIB))).containsExactly(10L);
    }

    /** Kapacitet objekta koji ga vodi po jedinicama (hoteli i sl.), a ne u facility_capacity. */
    @Test
    void fallsBackToUnitCapacity_whenFacilityCapacityMissing() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Apartmani", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("INSERT INTO str.facility_unit (id, active, facility_id, type_id, number_of_units)"
                + " VALUES (200, true, 10, 1011, 3)");
        jdbc.update("INSERT INTO str.facility_unit_capacity"
                + " (id, active, facility_unit_id, type_id, quantity) VALUES (300, true, 200, 1040, 4)");

        assertThat(list(OIB).getFirst().getBeds()).isEqualTo(4);
    }

    // -------------------------------------------------------------------------------------------
    // Kapacitet (B-3): isto pravilo za popis i claim
    // -------------------------------------------------------------------------------------------

    /**
     * Apartmani i kuće za odmor drže krevete u smještajnim sadržajima: kreveti jednog sadržaja ×
     * broj jednakih sadržaja. TuRegistar za „Vilu Luciju" (10 trokrevetnih soba) prikazuje 30.
     */
    @Test
    void countsBedsFromAccommodationContents_bedsTimesEqualContents() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Vila Lucija", SLUZBENIK, "2024-01-01 10:00:00");
        content(500, 10, 1046, 10, true);
        contentCapacity(600, 500, 1040, 3, true);
        content(501, 10, 1047, null, true); // sadržaj bez kreveta (igralište) ne mijenja zbroj
        capacity(101, 10, 1041, 2);

        FacilityListingRow row = list(OIB).getFirst();
        FacilityOwnershipRow owned = repository.findOwnership(10L).orElseThrow();

        assertThat(row.getBeds()).isEqualTo(30);
        assertThat(row.getAuxiliaryBeds()).isEqualTo(2);
        assertThat(row.getObjectLevelCapacity()).isFalse();
        assertThat(owned.getBeds()).isEqualTo(30);
        assertThat(FacilityClaimVerifier.maxGuests(owned)).isEqualTo(32);
    }

    /** W-8 (CDU preprod): 2 dvokrevetne sobe + 2 pomoćna kreveta — TuRegistar „4 + 2". */
    @Test
    void countsW8Apartment_asFourPlusTwo() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-w8", "1", SLUZBENIK, "2026-03-30 07:09:48");
        content(500, 10, 1045, 2, true);
        contentCapacity(600, 500, 1040, 2, true);
        capacity(101, 10, 1041, 2);

        FacilityListingRow row = list(OIB).getFirst();

        assertThat(row.getBeds()).isEqualTo(4);
        assertThat(row.getAuxiliaryBeds()).isEqualTo(2);
        assertThat(FacilityClaimVerifier.maxGuests(repository.findOwnership(10L).orElseThrow())).isEqualTo(6);
    }

    /** Svaka izmjena u eTurizmu ostavi stari redak neaktivnim — ne smije ući u zbroj. */
    @Test
    void ignoresInactiveContentAndContentCapacityRows() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Kuca", SLUZBENIK, "2024-01-01 10:00:00");
        content(500, 10, 1045, 2, false);           // stara verzija sadržaja
        contentCapacity(600, 500, 1040, 2, true);
        content(501, 10, 1045, 1, true);
        contentCapacity(601, 501, 1040, 2, false);  // stara verzija kapaciteta
        contentCapacity(602, 501, 1040, 2, true);

        assertThat(list(OIB).getFirst().getBeds()).isEqualTo(2);
        assertThat(repository.findOwnership(10L).orElseThrow().getBeds()).isEqualTo(2);
    }

    /**
     * Popis i claim čitaju isti kapacitet. Claim je ranije redak s {@code active} NULL brojao kao
     * aktivan, a popis nije — isti objekt je imao „-" u popisu i zaključan broj gostiju u formi.
     */
    @Test
    void listingAndClaimIgnoreSameCapacityRows() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba", SLUZBENIK, "2024-01-01 10:00:00");
        capacity(100, 10, 1040, 2);
        jdbc.update("INSERT INTO str.facility_capacity (id, active, facility_id, type_id, quantity)"
                + " VALUES (101, NULL, 10, 1040, 5), (102, false, 10, 1041, 3)");

        FacilityListingRow row = list(OIB).getFirst();
        FacilityOwnershipRow owned = repository.findOwnership(10L).orElseThrow();

        assertThat(row.getBeds()).isEqualTo(2);
        assertThat(owned.getBeds()).isEqualTo(2);
        assertThat(row.getAuxiliaryBeds()).isNull();
        assertThat(owned.getAuxiliaryBeds()).isNull();
    }

    /** Sobe drže krevete u facility_capacity; to pravilo ima prednost pred sadržajima. */
    @Test
    void prefersFacilityCapacity_overContents() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba", SLUZBENIK, "2024-01-01 10:00:00");
        capacity(100, 10, 1040, 2);
        content(500, 10, 1045, 3, true);
        contentCapacity(600, 500, 1040, 2, true);

        assertThat(list(OIB).getFirst().getBeds()).isEqualTo(2);
    }

    /**
     * Migracija je na svaku jedinicu objekta upisala kapacitet cijelog objekta (CDU: objekt s 3
     * jedinice, svaka s retcima 2, 3 i 4). Kreveti su tada kapacitet objekta, a broj gostiju
     * jedinice nije poznat (P-22).
     */
    @Test
    void marksObjectLevelCapacity_forMigratedObjectWithSeveralUnits() {
        migratedCase(900, 1);
        for (long id = 10; id <= 12; id++) {
            unit(id, 900, "uuid-migr", "Studio apartmani", MIGRACIJA, "2023-01-19 13:00:00." + id);
            capacity(id * 10, id, 1040, 2);
            capacity(id * 10 + 1, id, 1040, 3);
            capacity(id * 10 + 2, id, 1040, 4);
        }

        List<FacilityListingRow> rows = list(OIB);
        FacilityOwnershipRow owned = repository.findOwnership(11L).orElseThrow();

        assertThat(rows).extracting(FacilityListingRow::getObjectLevelCapacity).containsOnly(true);
        assertThat(rows).extracting(FacilityListingRow::getBeds).containsOnly(9);
        assertThat(owned.getObjectLevelCapacity()).isTrue();
        assertThat(FacilityClaimVerifier.maxGuests(owned)).isNull();
        assertThat(FacilityClaimVerifier.lockedFields(owned)).doesNotContain(FacilityClaimVerifier.FIELD_BEDS);
    }

    /** Migrirani objekt s jednom jedinicom: kapacitet je kapacitet te jedinice. */
    @Test
    void keepsUnitCapacity_forMigratedObjectWithOneUnit() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba", MIGRACIJA, "2023-01-19 13:00:00");
        content(500, 10, 1045, 1, true);
        contentCapacity(600, 500, 1040, 4, true);

        FacilityOwnershipRow owned = repository.findOwnership(10L).orElseThrow();

        assertThat(list(OIB).getFirst().getObjectLevelCapacity()).isFalse();
        assertThat(owned.getObjectLevelCapacity()).isFalse();
        assertThat(FacilityClaimVerifier.maxGuests(owned)).isEqualTo(4);
    }

    /** Novi eTurizam vodi kapacitet po jedinici (W-8: jedinice „1", „2"), pa se ne dira. */
    @Test
    void keepsUnitCapacity_forVerifiedObjectWithSeveralUnits() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-obj", "1", SLUZBENIK, "2024-01-01 10:00:00.001");
        unit(11, 900, "uuid-obj", "2", SLUZBENIK, "2024-01-01 10:00:00.002");
        capacity(100, 10, 1040, 2);
        capacity(110, 11, 1040, 4);

        List<FacilityListingRow> rows = list(OIB);

        assertThat(rows).extracting(FacilityListingRow::getObjectLevelCapacity).containsOnly(false);
        assertThat(rows).extracting(FacilityListingRow::getBeds).containsExactly(2, 4);
        assertThat(repository.findOwnership(11L).orElseThrow().getObjectLevelCapacity()).isFalse();
    }

    // -------------------------------------------------------------------------------------------
    // Vlasnički upit (claim i FacilityClaimVerifier)
    // -------------------------------------------------------------------------------------------

    @Test
    void findsOwnership_forFacilityClaimVerification() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba 1", SLUZBENIK, "2024-01-01 10:00:00");
        capacity(100, 10, 1040, 2);

        Optional<FacilityOwnershipRow> row = repository.findOwnership(10L);

        assertThat(row).isPresent();
        assertThat(row.get().getOib()).isEqualTo(OIB);
        assertThat(row.get().getSubtypeCode()).isEqualTo("FS_SOBA");
        assertThat(row.get().getBeds()).isEqualTo(2);
        assertThat(row.get().getAuxiliaryBeds()).isNull();
        assertThat(row.get().getActive()).isTrue();
        assertThat(row.get().getCurrent()).isTrue();
        assertThat(row.get().getOwnerFullName()).isEqualTo("Tonci Beros");
        // Literal u PRIKAZ_ZA_OIB i konstanta koju čita verifier moraju biti isti kod
        assertThat(row.get().getBusinessStatusCode()).isEqualTo(StrFacilityRepository.ACTIVE_BUSINESS_STATUS);
        assertThat(FacilityClaimVerifier.isActive(row.get())).isTrue();
        assertThat(ids(list(OIB))).containsExactly(10L);
    }

    /** Migrirana jedinica je aktualna, pa smije dobiti RB (odluka 6. 10. 2026.). */
    @Test
    void findsOwnership_ofMigratedUnit_asCurrent() {
        migratedCase(900, 1);
        unit(10, 900, "uuid-10", "Migrirana", MIGRACIJA, "2023-01-19 13:00:00");

        FacilityOwnershipRow row = repository.findOwnership(10L).orElseThrow();

        assertThat(row.getCurrent()).isTrue();
        assertThat(FacilityClaimVerifier.isActive(row)).isTrue();
    }

    /** Zapis koji nije aktualan (predmet u rješavanju) vraća se s {@code current = false}. */
    @Test
    void findsOwnership_ofRowThatIsNotCurrent() {
        businessCase(900, 1, STATUS_U_RJESAVANJU);
        document(900, RJESENJE, "2024-01-01 00:00:00");
        unit(10, 900, "uuid-10", "U rjesavanju", SLUZBENIK, "2024-01-01 10:00:00");

        FacilityOwnershipRow row = repository.findOwnership(10L).orElseThrow();

        assertThat(row.getCurrent()).isFalse();
        assertThat(FacilityClaimVerifier.isActive(row)).isFalse();
    }

    /** Vlasnički upit vraća odjavljen objekt (da verifier kaže zašto), ali ga ne pušta. */
    @Test
    void findsOwnership_ofDeregisteredUnit_asInactive() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Odjavljena soba", SLUZBENIK, "2024-01-01 10:00:00");
        businessStatus(10, 1031L);

        FacilityOwnershipRow row = repository.findOwnership(10L).orElseThrow();

        assertThat(row.getBusinessStatusCode()).isEqualTo("FBS_INACTIVE");
        assertThat(FacilityClaimVerifier.isActive(row)).isFalse();
    }

    @Test
    void findsOwnership_withoutBusinessStatus_asInactive() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba bez statusa", SLUZBENIK, "2024-01-01 10:00:00");
        businessStatus(10, null);

        FacilityOwnershipRow row = repository.findOwnership(10L).orElseThrow();

        assertThat(row.getBusinessStatusCode()).isNull();
        assertThat(FacilityClaimVerifier.isActive(row)).isFalse();
    }

    /** Pomoćni kreveti ulaze u maksimalan broj gostiju, pa ih vlasnički upit mora vratiti. */
    @Test
    void findsOwnership_withAuxiliaryBeds() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Soba 1", SLUZBENIK, "2024-01-01 10:00:00");
        capacity(100, 10, 1040, 4);
        capacity(101, 10, 1041, 2);

        FacilityOwnershipRow row = repository.findOwnership(10L).orElseThrow();

        assertThat(row.getBeds()).isEqualTo(4);
        assertThat(row.getAuxiliaryBeds()).isEqualTo(2);
        assertThat(FacilityClaimVerifier.maxGuests(row)).isEqualTo(6);
    }

    /** Isti fallback na jedinice kao za krevete — inače bi zbroj kod takvih objekata izgubio pomoćne. */
    @Test
    void findsOwnership_fallsBackToUnitCapacity_forBedsAndAuxiliaryBeds() {
        verifiedCase(900, 1);
        unit(10, 900, "uuid-10", "Apartmani", SLUZBENIK, "2024-01-01 10:00:00");
        jdbc.update("INSERT INTO str.facility_unit (id, active, facility_id, type_id, number_of_units)"
                + " VALUES (200, true, 10, 1011, 3)");
        jdbc.update("INSERT INTO str.facility_unit_capacity"
                + " (id, active, facility_unit_id, type_id, quantity) VALUES (300, true, 200, 1040, 4)");
        jdbc.update("INSERT INTO str.facility_unit_capacity"
                + " (id, active, facility_unit_id, type_id, quantity) VALUES (301, true, 200, 1041, 1)");

        FacilityOwnershipRow row = repository.findOwnership(10L).orElseThrow();

        assertThat(row.getBeds()).isEqualTo(4);
        assertThat(row.getAuxiliaryBeds()).isEqualTo(1);
        // Popis objekata mora vidjeti isti podatak kao provjera
        assertThat(list(OIB).getFirst().getAuxiliaryBeds()).isEqualTo(1);
    }

    @Test
    void findsNoOwnership_forUnknownFacility() {
        assertThat(repository.findOwnership(404L)).isEmpty();
    }

    // -------------------------------------------------------------------------------------------

    private List<FacilityListingRow> list(String oib) {
        return repository.findListingByOib(oib, CODES, 20, 0);
    }

    /** Predmet novog sustava s izvršnim rješenjem (dokument ima isti id kao predmet). */
    private void verifiedCase(long caseId, long subjectVersionId) {
        businessCase(caseId, subjectVersionId, STATUS_IZVRSNO);
        document(caseId, RJESENJE, "2024-01-01 00:00:00");
    }

    /** Migrirani predmet: bez statusa i bez datuma izvršnosti, kao svi migrirani na CDU. */
    private void migratedCase(long caseId, long subjectVersionId) {
        businessCase(caseId, subjectVersionId, null);
        document(caseId, RJESENJE, null);
    }

    private void businessCase(long id, long subjectVersionId, Long statusId) {
        jdbc.update("""
                INSERT INTO str.business_case (id, active, status_type_id,
                                               jurisdiction_organizational_unit_id, subject_version_id)
                VALUES (?, true, ?, 1, ?)
                """, id, statusId, subjectVersionId);
    }

    private void document(long caseId, String subtypeCode, String executionDate) {
        jdbc.update("""
                INSERT INTO str.document (id, active, business_case_id, subtype_code, execution_date)
                VALUES (?, true, ?, ?, CAST(? AS TIMESTAMP))
                """, caseId, caseId, subtypeCode, executionDate);
    }

    private void verification(long id, Long unverifiedCaseId, Long verifiedCaseId, long statusId) {
        jdbc.update("""
                INSERT INTO str.business_case_verification
                  (id, unverified_business_case_id, verified_business_case_id, status_id)
                VALUES (?, ?, ?, ?)
                """, id, unverifiedCaseId, verifiedCaseId, statusId);
    }

    /** Jedinica (soba) na predmetu {@code caseId}; {@code facility.subject_version_id} kao u predmetu. */
    private void unit(long id, long caseId, String systemUuid, String name, String createdBy,
                      String createdDate) {
        unitWithoutType(id, caseId, systemUuid, name, createdBy, createdDate);
        jdbc.update("INSERT INTO str.facility_type (id, active, facility_id, type_id, sub_type_id)"
                + " VALUES (?, true, ?, 1000, 1010)", id * 10, id);
    }

    private void unitWithoutType(long id, long caseId, String systemUuid, String name, String createdBy,
                                 String createdDate) {
        jdbc.update("""
                INSERT INTO str.facility (id, active, subject_version_id, name, system_uuid,
                                          document_id, address_id, category_id, business_status_id,
                                          same_address_subject, registration_number, created_by,
                                          created_date, historical)
                SELECT ?, true, bc.subject_version_id, ?, ?, ?, 41, 1020, 1030, false, NULL, ?,
                       CAST(? AS TIMESTAMP), NULL
                  FROM str.business_case bc WHERE bc.id = ?
                """, id, name, systemUuid, caseId, createdBy, createdDate, caseId);
    }

    private void businessStatus(long facilityId, Long statusId) {
        jdbc.update("UPDATE str.facility SET business_status_id = ? WHERE id = ?", statusId, facilityId);
    }

    private void capacity(long id, long facilityId, long typeId, int quantity) {
        jdbc.update("INSERT INTO str.facility_capacity (id, active, facility_id, type_id, quantity)"
                + " VALUES (?, true, ?, ?, ?)", id, facilityId, typeId, quantity);
    }

    private void content(long id, long facilityId, long typeId, Integer quantity, boolean active) {
        jdbc.update("INSERT INTO str.facility_content (id, active, facility_id, type_id, quantity)"
                + " VALUES (?, ?, ?, ?, ?)", id, active, facilityId, typeId, quantity);
    }

    private void contentCapacity(long id, long contentId, long typeId, int quantity, boolean active) {
        jdbc.update("INSERT INTO str.facility_content_capacity"
                + " (id, active, facility_content_id, type_id, quantity) VALUES (?, ?, ?, ?, ?)",
                id, active, contentId, typeId, quantity);
    }

    private static List<Long> ids(List<FacilityListingRow> rows) {
        return rows.stream().map(FacilityListingRow::getFacilityId).toList();
    }
}
