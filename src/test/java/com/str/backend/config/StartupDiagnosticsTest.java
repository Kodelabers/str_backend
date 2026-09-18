package com.str.backend.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dijagnostika ne smije biti razlog da servis ne krene. Ovi testovi drže to svojstvo — sve ostalo
 * su ionako samo redovi u logu.
 */
class StartupDiagnosticsTest {

    private static MockEnvironment env() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://host:5431/eturizam")
                .withProperty("nias.saml.enabled", "true")
                .withProperty("app.captcha.enabled", "true")
                .withProperty("app.captcha.hmac-key", "change-me-in-production");
    }

    @Test
    @DisplayName("baza koja puca ne obara start")
    void survives_databaseFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(any(ConnectionCallback.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("veza pukla"));

        assertThatCode(() -> new StartupDiagnostics(env(), jdbc).report()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ne-PostgreSQL baza preskace sonde prava umjesto da puca")
    void skipsPrivilegeProbes_onNonPostgres() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn("H2");

        new StartupDiagnostics(env(), jdbc).report();

        // has_schema_privilege / current_user ne postoje izvan Postgresa — ne smiju se ni zvati
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class));
        verify(jdbc, never()).queryForObject(anyString(), any(RowMapper.class));
    }

    @Test
    @DisplayName("prazna konfiguracija ne obara start")
    void survives_emptyEnvironment() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn("H2");

        assertThatCode(() -> new StartupDiagnostics(new MockEnvironment(), jdbc).report())
                .doesNotThrowAnyException();
    }

    /**
     * Naziv tijela otvara rečenicu uvoda svakog akta, pa mali početak nije stvar stila nego kvar
     * konfiguracije. Zaglavlje i potpis ga pišu velikim slovima sami, zato se na njima ne vidi —
     * na CDU-u je tako prošao deploy s „ministarstvo turizma i sporta" (18.09.2026.).
     */
    @Test
    @DisplayName("naziv tijela malim pocetnim slovom prijavljuje se kao WARN")
    void warnsOnLowercaseAuthorityName() {
        List<ILoggingEvent> zapisi = uhvatiLog(env()
                .withProperty("str.documents.tijelo.naziv", "ministarstvo turizma i sporta"));

        assertThat(zapisi).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("naziv_pocinje_malim_slovom"));
    }

    @Test
    @DisplayName("ispravan naziv i neobavezan potpisnik ne dizu alarm")
    void noWarning_forProperNameAndMissingSignatory() {
        List<ILoggingEvent> zapisi = uhvatiLog(env()
                .withProperty("str.documents.tijelo.naziv", "Ministarstvo turizma i sporta"));

        assertThat(zapisi).noneMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("naziv_pocinje_malim_slovom"));
        // Ime službene osobe smije ostati prazno — ne smije se prijaviti kao pregaženi default.
        assertThat(zapisi).anyMatch(e -> e.getFormattedMessage()
                .contains("potpisnik=nije postavljen (neobavezno)"));
    }

    private static List<ILoggingEvent> uhvatiLog(MockEnvironment env) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn("H2");
        Logger logger = (Logger) LoggerFactory.getLogger(StartupDiagnostics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new StartupDiagnostics(env, jdbc).report();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
