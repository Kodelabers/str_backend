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
}
