package com.str.backend.auth.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upit nad tablicama sheme {@code str}, oblika kao na devu (application_user.username nosi OIB,
 * dodjela u application_user_roles). Tablice se ovdje stvaraju same — u produkciji ih vodi eTurizam.
 */
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InternalUserRepository.class)
// Repozitorij čita izvan okolne transakcije (NOT_SUPPORTED), pa ne bi vidio retke iz
// nepotvrđene testne transakcije — podaci se zato potvrđuju i čiste ručno.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InternalUserRepositoryTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired InternalUserRepository repository;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS str");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.application_role (sys_name VARCHAR(255) PRIMARY KEY, name VARCHAR(255) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.application_user (id BIGINT PRIMARY KEY, active BOOLEAN NOT NULL, username VARCHAR(255) NOT NULL, internal BOOLEAN, first_name VARCHAR(255), last_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.application_user_roles (users_id BIGINT NOT NULL, roles_sys_name VARCHAR(255) NOT NULL)");
        // Organizacija i nadležnost (findOrganization). Tablice dijele i drugi testovi istog konteksta
        // (StrFacilityListingQueryTest), pa se stvaraju s njihovim stupcima, a naši se dodaju ako ih nema.
        jdbc.execute("ALTER TABLE str.application_user ADD COLUMN IF NOT EXISTS internal_organization_id BIGINT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.codebook_element (id BIGINT PRIMARY KEY, active BOOLEAN, code VARCHAR(100), name VARCHAR(255))");
        jdbc.execute("ALTER TABLE str.codebook_element ADD COLUMN IF NOT EXISTS code VARCHAR(100)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.internal_organization (id BIGINT PRIMARY KEY, active BOOLEAN NOT NULL, name VARCHAR(255) NOT NULL, type_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.organizational_unit (id BIGINT PRIMARY KEY)");
        jdbc.execute("ALTER TABLE str.organizational_unit ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE");
        jdbc.execute("ALTER TABLE str.organizational_unit ADD COLUMN IF NOT EXISTS internal_organization_id BIGINT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.settlement_jurisdiction (id BIGINT PRIMARY KEY, active BOOLEAN NOT NULL, organizational_unit_id BIGINT NOT NULL, settlement_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.county (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("ALTER TABLE str.county ADD COLUMN IF NOT EXISTS name VARCHAR(255)");
        jdbc.execute("ALTER TABLE str.county ADD COLUMN IF NOT EXISTS external_code VARCHAR(16)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.municipality (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("ALTER TABLE str.municipality ADD COLUMN IF NOT EXISTS county_id BIGINT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.settlement (id BIGINT PRIMARY KEY, name VARCHAR(255), postal_code VARCHAR(16))");
        jdbc.execute("ALTER TABLE str.settlement ADD COLUMN IF NOT EXISTS municipality_id BIGINT");
        jdbc.update("DELETE FROM str.settlement_jurisdiction WHERE id >= 990000");
        jdbc.update("DELETE FROM str.organizational_unit WHERE id >= 990000");
        jdbc.update("DELETE FROM str.internal_organization WHERE id >= 990000");
        jdbc.update("DELETE FROM str.settlement WHERE id >= 990000");
        jdbc.update("DELETE FROM str.municipality WHERE id >= 990000");
        jdbc.update("DELETE FROM str.county WHERE id >= 990000");
        jdbc.update("DELETE FROM str.codebook_element WHERE id >= 990000");
        jdbc.update("DELETE FROM str.application_user_roles");
        jdbc.update("DELETE FROM str.application_user");
        jdbc.update("DELETE FROM str.application_role");
        jdbc.update("INSERT INTO str.application_role (sys_name, name) VALUES ('RN_MANAGEMENT', 'Upravljanje registracijskim brojevima'), ('VERIFIER', 'Verifikator')");
    }

    @Test
    void internalActiveUser_returnsAllAssignedRoles() {
        user(1, "12345678903", true, true, "RN_MANAGEMENT", "VERIFIER");

        assertThat(repository.findRoles("12345678903")).containsExactlyInAnyOrder("RN_MANAGEMENT", "VERIFIER");
    }

    @Test
    void notInternal_returnsNothing() {
        user(1, "12345678903", false, true, "RN_MANAGEMENT");

        assertThat(repository.findRoles("12345678903")).isEmpty();
    }

    @Test
    void internalNull_returnsNothing() {
        user(1, "12345678903", null, true, "RN_MANAGEMENT");

        assertThat(repository.findRoles("12345678903")).isEmpty();
    }

    @Test
    void inactiveUser_returnsNothing() {
        user(1, "12345678903", true, false, "RN_MANAGEMENT");

        assertThat(repository.findRoles("12345678903")).isEmpty();
    }

    @Test
    void matchesOibExactly_neverByPrefixOrPadding() {
        user(1, "12345678903", true, true, "RN_MANAGEMENT");

        assertThat(repository.findRoles("1234567890")).isEmpty();
        assertThat(repository.findRoles(" 12345678903")).isEmpty();
        assertThat(repository.findRoles("12345678903 ")).isEmpty();
        assertThat(repository.findRoles("%")).isEmpty();
    }

    @Test
    void otherUsersRoles_doNotLeak() {
        user(1, "12345678903", true, true, "RN_MANAGEMENT");
        user(2, "77777777778", true, true);

        assertThat(repository.findRoles("77777777778")).isEmpty();
    }

    private void user(long id, String username, Boolean internal, boolean active, String... roles) {
        jdbc.update("INSERT INTO str.application_user (id, active, username, internal) VALUES (?, ?, ?, ?)",
                id, active, username, internal);
        for (String role : roles) {
            jdbc.update("INSERT INTO str.application_user_roles (users_id, roles_sys_name) VALUES (?, ?)", id, role);
        }
    }

    @Test
    void findNames_returnsInternalUsersOnly() {
        user(1, "12345678903", true, true, "RN_MANAGEMENT");
        user(2, "77777777778", false, true);
        jdbc.update("UPDATE str.application_user SET first_name = 'Ana', last_name = 'Službenica' WHERE id = 1");
        jdbc.update("UPDATE str.application_user SET first_name = 'Ivo', last_name = 'Vanjski' WHERE id = 2");

        assertThat(repository.findNames(List.of("12345678903", "77777777778", "99999999990")))
                .containsExactly(Map.entry("12345678903", "Ana Službenica"));
    }

    @Test
    void findNames_emptyInput_noQuery() {
        assertThat(repository.findNames(List.of())).isEmpty();
    }

    // --- Organizacija i nadležnost (opseg službenika po županiji) ---

    /** Split (17) s dva naselja i Zagreb (21); MINTS ima jedinicu nadležnu za oba. */
    private void organizations() {
        jdbc.update("INSERT INTO str.codebook_element (id, code) VALUES (990570, 'IOT_MINTS'), (990571, 'IOT_ZUPANIJA')");
        jdbc.update("INSERT INTO str.county (id, name, external_code) VALUES (990017, 'Splitsko-dalmatinska županija', '17'), (990021, 'Grad Zagreb', '21')");
        jdbc.update("INSERT INTO str.municipality (id, county_id) VALUES (990101, 990017), (990102, 990017), (990201, 990021)");
        jdbc.update("INSERT INTO str.settlement (id, municipality_id) VALUES (990001, 990101), (990002, 990102), (990003, 990201)");
        jdbc.update("INSERT INTO str.internal_organization (id, active, name, type_id) VALUES (990001, TRUE, 'Ministarstvo turizma i sporta', 990570), (990018, TRUE, 'Splitsko-dalmatinska županija', 990571), (990099, FALSE, 'Ugašena županija', 990571)");
        jdbc.update("INSERT INTO str.organizational_unit (id, active, internal_organization_id) VALUES (990136, TRUE, 990018), (990137, TRUE, 990018), (990039, TRUE, 990001), (990199, TRUE, 990099)");
        jdbc.update("INSERT INTO str.settlement_jurisdiction (id, active, organizational_unit_id, settlement_id) VALUES (990501, TRUE, 990136, 990001), (990502, TRUE, 990137, 990002), (990503, TRUE, 990039, 990001), (990504, TRUE, 990039, 990003), (990505, FALSE, 990136, 990003), (990506, TRUE, 990199, 990003)");
    }

    private void organizationOf(long userId, long organizationId) {
        jdbc.update("UPDATE str.application_user SET internal_organization_id = ? WHERE id = ?", organizationId, userId);
    }

    /** Nadležnost svih jedinica organizacije, bez neaktivnih zapisa nadležnosti. */
    @Test
    void findOrganization_countyOrganization_coversItsCounty() {
        organizations();
        user(1, "12345678903", true, true, "RN_MANAGEMENT");
        organizationOf(1, 990018);

        assertThat(repository.findOrganizations("12345678903")).containsExactly(new InternalUserRepository.Organization(
                "Splitsko-dalmatinska županija", "IOT_ZUPANIJA", 1, "17", "Splitsko-dalmatinska županija"));
    }

    @Test
    void findOrganization_ministry_coversSeveralCounties() {
        organizations();
        user(1, "12345678903", true, true, "RN_MANAGEMENT");
        organizationOf(1, 990001);

        InternalUserRepository.Organization org = repository.findOrganizations("12345678903").get(0);
        assertThat(org.orgType()).isEqualTo("IOT_MINTS");
        assertThat(org.counties()).isEqualTo(2);
    }

    @Test
    void findOrganization_withoutOrganization_orInactiveOne_isEmpty() {
        organizations();
        user(1, "12345678903", true, true, "RN_MANAGEMENT");
        user(2, "77777777778", true, true, "RN_MANAGEMENT");
        organizationOf(2, 990099);

        assertThat(repository.findOrganizations("12345678903")).isEmpty();
        assertThat(repository.findOrganizations("77777777778")).isEmpty();
    }

    @Test
    void findOrganization_notInternal_isEmpty() {
        organizations();
        user(1, "12345678903", false, true, "RN_MANAGEMENT");
        organizationOf(1, 990018);

        assertThat(repository.findOrganizations("12345678903")).isEmpty();
    }

    /**
     * Isti OIB s dva aktivna interna zapisa u različitim organizacijama: po redak za svaku, ne
     * zbroj — odluku donosi resolver (cijela RH).
     */
    @Test
    void findOrganizations_sameOibInTwoOrganizations_oneRowEach() {
        organizations();
        user(1, "12345678903", true, true, "RN_MANAGEMENT");
        user(2, "12345678903", true, true);
        organizationOf(1, 990018);
        organizationOf(2, 990001);

        assertThat(repository.findOrganizations("12345678903"))
                .extracting(InternalUserRepository.Organization::orgType)
                .containsExactly("IOT_MINTS", "IOT_ZUPANIJA");
    }
}
