package com.str.backend.str;

import com.str.backend.str.StrFacilityRepository.FacilityListingRow;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
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
 * Native query popisa objekata na pravoj bazi (H2), jer je dedup ono što nosi najveći rizik.
 *
 * <p>Tablice koje query joina nemaju entitete, pa ih Hibernate ne stvara — ovaj test ih kreira
 * sam i dopunjava {@code str.facility} kolonama koje {@link StrFacilityEntity} ne mapira (entitet
 * je namjerno minimalan i {@code @Immutable}). Struktura odgovara pravoj eTurizam shemi, provjerenoj
 * na dev-u.
 */
@SpringBootTest
@ActiveProfiles("test")
class StrFacilityListingQueryTest {

    private static final String OIB = "06756460531";
    private static final String OTHER_OIB = "12312312316";
    private static final List<String> CODES = List.of("FS_SOBA", "FS_APARTMAN", "FS_KUCA_ZA_ODMOR");

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

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.codebook_element (
                  id BIGINT PRIMARY KEY, active BOOLEAN, code VARCHAR(100), name VARCHAR(255))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.document (
                  id BIGINT PRIMARY KEY, active BOOLEAN, business_case_id BIGINT)
                """);
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
                "facility_unit_capacity", "document", "address", "county", "municipality",
                "settlement", "street", "house_number", "codebook_element",
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
                  (1040, true, 'CAT_BROJ_KREVETA', 'Broj kreveta'),
                  (1041, true, 'CAT_BROJ_POM_KREVETA', 'Broj pomocnih kreveta')
                """);
        jdbc.execute("""
                INSERT INTO str.subject (id, active, jips) VALUES
                  (1, true, '06756460531'), (2, true, '12312312316'), (3, false, '06756460531')
                """);
        jdbc.execute("""
                INSERT INTO str.subject_version (id, active, subject_id, historical) VALUES
                  (1, true, 1, false), (2, true, 2, false), (3, true, 3, false)
                """);
        jdbc.execute("""
                INSERT INTO str.county (id, name) VALUES (91, 'Splitsko-dalmatinska zupanija')
                """);
        jdbc.execute("INSERT INTO str.municipality (id, name) VALUES (81, 'Makarska')");
        jdbc.execute("INSERT INTO str.settlement (id, name, postal_code) VALUES (71, 'Makarska', '21300')");
        jdbc.execute("INSERT INTO str.street (id, name) VALUES (61, 'Kraljevska')");
        jdbc.execute("INSERT INTO str.house_number (id, name) VALUES (51, '88')");
        jdbc.execute("""
                INSERT INTO str.address (id, active, county_id, municipality_id, settlement_id,
                                         street_id, house_number_id, full_address)
                VALUES (41, true, 91, 81, 71, 61, 51, 'Kraljevska 88, 21300 Makarska')
                """);
        jdbc.execute("""
                INSERT INTO str.document (id, active, business_case_id) VALUES
                  (31, true, 900), (32, true, 901), (33, true, 902), (34, true, 903),
                  (35, true, 904), (36, true, 905), (37, true, 905)
                """);
    }

    @Test
    void returnsOwnFacilities_withTypeAddressAndCapacity() {
        facility(10, 1, "Soba 1", "uuid-10", 31, true);
        type(10, 1010);
        capacity(100, 10, 1040, 2);
        capacity(101, 10, 1041, 1);

        List<FacilityListingRow> rows = repository.findListingByOib(OIB, CODES, 20, 0);

        assertThat(rows).hasSize(1);
        FacilityListingRow row = rows.getFirst();
        assertThat(row.getFacilityId()).isEqualTo(10L);
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

    @Test
    void excludesFacilitiesOfOtherLessors() {
        facility(10, 1, "Moja soba", "uuid-10", 31, true);
        type(10, 1010);
        facility(11, 2, "Tuda soba", "uuid-11", 32, true);
        type(11, 1010);

        assertThat(ids(repository.findListingByOib(OIB, CODES, 20, 0))).containsExactly(10L);
    }

    /** Dedup po system_uuid: prikazuje se samo najnoviji zapis istog objekta. */
    @Test
    void keepsOnlyNewestRowPerSystemUuid() {
        facility(10, 1, "Stara verzija", "uuid-shared", 31, true);
        type(10, 1010);
        facility(12, 1, "Nova verzija", "uuid-shared", 32, true);
        type(12, 1010);

        List<FacilityListingRow> rows = repository.findListingByOib(OIB, CODES, 20, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getName()).isEqualTo("Nova verzija");
    }

    /** Objekti u radu nemaju system_uuid, pa se grupiraju po business_case_id dokumenta. */
    @Test
    void dedupsByBusinessCaseId_whenSystemUuidMissing() {
        facility(13, 1, "Predlozak stari", null, 36, true);  // document 36 → business_case 905
        type(13, 1010);
        facility(14, 1, "Predlozak novi", null, 37, true);   // document 37 → isti business_case 905
        type(14, 1010);

        List<FacilityListingRow> rows = repository.findListingByOib(OIB, CODES, 20, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getName()).isEqualTo("Predlozak novi");
    }

    /**
     * Objekt prenesen na drugog vlasnika: noviji zapis istog system_uuid pripada drugom subjektu,
     * pa stari vlasnik objekt više ne vidi. Dedup unutar OIB-a to sam ne bi uhvatio.
     */
    @Test
    void hidesFacilitySupersededByRowOfAnotherLessor() {
        facility(10, 1, "Prodana soba", "uuid-transfer", 31, true);
        type(10, 1010);
        facility(20, 2, "Ista soba, novi vlasnik", "uuid-transfer", 32, true);
        type(20, 1010);

        assertThat(repository.findListingByOib(OIB, CODES, 20, 0)).isEmpty();
        assertThat(ids(repository.findListingByOib(OTHER_OIB, CODES, 20, 0))).containsExactly(20L);
    }

    /** active se filtrira nakon dedupa — stariji aktivni zapis ne smije "oživjeti" objekt. */
    @Test
    void excludesInactiveFacility_withoutRevivingOlderActiveRow() {
        facility(10, 1, "Aktivna stara", "uuid-shared", 31, true);
        type(10, 1010);
        facility(12, 1, "Neaktivna nova", "uuid-shared", 32, false);
        type(12, 1010);

        assertThat(repository.findListingByOib(OIB, CODES, 20, 0)).isEmpty();
    }

    /**
     * Objekti bez {@code system_uuid} I bez dokumenta: bucket pada na vlastiti id, pa se ne skupe
     * svi u istu grupu. Bez toga bi {@code PARTITION BY NULL} od svih takvih objekata jednog
     * iznajmljivača prikazao samo najnoviji.
     */
    @Test
    void doesNotCollapseFacilitiesWithoutUuidAndWithoutDocument() {
        jdbc.update("INSERT INTO str.facility (id, active, subject_version_id, name, system_uuid,"
                + " document_id, address_id, category_id, business_status_id, same_address_subject)"
                + " VALUES (16, true, 1, 'Bez dokumenta A', NULL, NULL, 41, 1020, 1030, false)");
        type(16, 1010);
        jdbc.update("INSERT INTO str.facility (id, active, subject_version_id, name, system_uuid,"
                + " document_id, address_id, category_id, business_status_id, same_address_subject)"
                + " VALUES (17, true, 1, 'Bez dokumenta B', NULL, NULL, 41, 1020, 1030, false)");
        type(17, 1010);

        assertThat(ids(repository.findListingByOib(OIB, CODES, 20, 0))).containsExactly(16L, 17L);
        assertThat(repository.countListingByOib(OIB, CODES)).isEqualTo(2);
    }

    /**
     * {@code facility_type.active} u eTurizmu smije biti NULL — njihov vlastiti view ga ne filtrira.
     * Uz {@code active = true} objekt bi ostao bez vrste i ispao s popisa u cijelosti.
     */
    @Test
    void resolvesType_whenFacilityTypeActiveIsNull() {
        facility(10, 1, "Soba 1", "uuid-10", 31, true);
        jdbc.update("INSERT INTO str.facility_type (id, active, facility_id, type_id, sub_type_id)"
                + " VALUES (100, NULL, 10, 1000, 1010)");

        List<FacilityListingRow> rows = repository.findListingByOib(OIB, CODES, 20, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSubtypeCode()).isEqualTo("FS_SOBA");
    }

    /**
     * Zapis subjekta se s vremenom nadjača novijim, pa stari ostane {@code active = false}.
     * Objekt vodi na verziju tog starog zapisa, a OIB je isti — mora se i dalje prikazati.
     */
    @Test
    void includesFacilitiesOfSupersededSubjectRow() {
        facility(18, 3, "Soba na starom subjektu", "uuid-18", 31, true); // subject_version 3 → subject 3 (active = false)
        type(18, 1010);

        assertThat(ids(repository.findListingByOib(OIB, CODES, 20, 0))).containsExactly(18L);
        assertThat(repository.findOwnership(18L).orElseThrow().getOib()).isEqualTo(OIB);
    }

    /** Iznajmljivač u eTurizmu može imati i restoran — na dashboard smještaja ne ide. */
    @Test
    void excludesNonAccommodationSubtypes() {
        facility(10, 1, "Soba 1", "uuid-10", 31, true);
        type(10, 1010);
        facility(15, 1, "Pizzeria", "uuid-15", 33, true);
        jdbc.update("INSERT INTO str.facility_type (id, active, facility_id, type_id, sub_type_id)"
                + " VALUES (?, true, ?, 1001, 1014)", 150, 15);

        assertThat(ids(repository.findListingByOib(OIB, CODES, 20, 0))).containsExactly(10L);
    }

    @Test
    void paginatesAndCountsConsistently() {
        facility(10, 1, "Soba 1", "uuid-10", 31, true);
        type(10, 1010);
        facility(11, 1, "Soba 2", "uuid-11", 32, true);
        type(11, 1011);
        facility(12, 1, "Soba 3", "uuid-12", 33, true);
        type(12, 1010);

        assertThat(repository.countListingByOib(OIB, CODES)).isEqualTo(3);
        assertThat(ids(repository.findListingByOib(OIB, CODES, 2, 0))).containsExactly(10L, 11L);
        assertThat(ids(repository.findListingByOib(OIB, CODES, 2, 2))).containsExactly(12L);
    }

    /** Kapacitet objekta koji ga vodi po jedinicama (hoteli i sl.), a ne u facility_capacity. */
    @Test
    void fallsBackToUnitCapacity_whenFacilityCapacityMissing() {
        facility(10, 1, "Apartmani", "uuid-10", 31, true);
        type(10, 1011);
        jdbc.update("INSERT INTO str.facility_unit (id, active, facility_id, type_id, number_of_units)"
                + " VALUES (200, true, 10, 1011, 3)");
        jdbc.update("INSERT INTO str.facility_unit_capacity"
                + " (id, active, facility_unit_id, type_id, quantity) VALUES (300, true, 200, 1040, 4)");

        assertThat(repository.findListingByOib(OIB, CODES, 20, 0).getFirst().getBeds()).isEqualTo(4);
    }

    @Test
    void findsOwnership_forFacilityClaimVerification() {
        facility(10, 1, "Soba 1", "uuid-10", 31, true);
        type(10, 1010);
        capacity(100, 10, 1040, 2);

        Optional<FacilityOwnershipRow> row = repository.findOwnership(10L);

        assertThat(row).isPresent();
        assertThat(row.get().getOib()).isEqualTo(OIB);
        assertThat(row.get().getSubtypeCode()).isEqualTo("FS_SOBA");
        assertThat(row.get().getBeds()).isEqualTo(2);
        assertThat(row.get().getActive()).isTrue();
    }

    private void facility(long id, long subjectVersionId, String name, String systemUuid,
                          long documentId, boolean active) {
        jdbc.update("""
                INSERT INTO str.facility (id, active, subject_version_id, name, system_uuid,
                                          document_id, address_id, category_id, business_status_id,
                                          same_address_subject, registration_number)
                VALUES (?, ?, ?, ?, ?, ?, 41, 1020, 1030, false, NULL)
                """, id, active, subjectVersionId, name, systemUuid, documentId);
    }

    private void type(long facilityId, long subTypeId) {
        jdbc.update("INSERT INTO str.facility_type (id, active, facility_id, type_id, sub_type_id)"
                + " VALUES (?, true, ?, 1000, ?)", facilityId * 10, facilityId, subTypeId);
    }

    private void capacity(long id, long facilityId, long typeId, int quantity) {
        jdbc.update("INSERT INTO str.facility_capacity (id, active, facility_id, type_id, quantity)"
                + " VALUES (?, true, ?, ?, ?)", id, facilityId, typeId, quantity);
    }

    private static List<Long> ids(List<FacilityListingRow> rows) {
        return rows.stream().map(FacilityListingRow::getFacilityId).toList();
    }
}
