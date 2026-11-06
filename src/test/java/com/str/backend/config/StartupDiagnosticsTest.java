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

    @Test
    @DisplayName("zurna verifikacija: nedostajuce pravo uz ukljucenu zastavicu je WARN s popisom")
    void verifikacija_warnsWithMissingPrivileges() {
        List<ILoggingEvent> zapisi = uhvatiLogPostgres(env()
                .withProperty("str.eturizam.verifikacija.enabled", "true")
                .withProperty("str.eturizam.verifikacija.obavijesti.enabled", "true"),
                "select has_table_privilege('str.korisnicka_obavijest','INSERT')");

        assertThat(zapisi).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().startsWith("startup_eturizam_verifikacija enabled=true")
                && e.getFormattedMessage().contains("nedostaje=[str.korisnicka_obavijest:INSERT]"));
    }

    @Test
    @DisplayName("zurna verifikacija: bez obavijesti provjeravaju se samo prava zahtjeva")
    void verifikacija_withoutNotifications_checksOnlyRequestTable() {
        List<ILoggingEvent> zapisi = uhvatiLogPostgres(env()
                .withProperty("str.eturizam.verifikacija.enabled", "true"),
                "select has_table_privilege('str.korisnicka_obavijest','INSERT')",
                "select has_table_privilege('str.application_user','SELECT')");

        assertThat(zapisi).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().equals(
                        "startup_eturizam_verifikacija enabled=true obavijesti=false nedostaje=[]"));
    }

    @Test
    @DisplayName("zurna verifikacija: ugasena zastavica ne dize WARN ni bez prava")
    void verifikacija_disabled_onlyInfo() {
        List<ILoggingEvent> zapisi = uhvatiLogPostgres(env(),
                "select has_table_privilege('str.verification_request','SELECT')",
                "select has_table_privilege('str.verification_request','INSERT')");

        assertThat(zapisi).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("startup_eturizam_verifikacija enabled=false")
                && e.getFormattedMessage().contains("str.verification_request:SELECT, str.verification_request:INSERT"));
        assertThat(zapisi).noneMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().startsWith("startup_eturizam_verifikacija"));
    }

    @Test
    @DisplayName("zurna verifikacija: preduga sifra i predlozak bez {rn} su ERROR, i bez PostgreSQL-a")
    void verifikacija_invalidNotificationConfig_isError() {
        List<ILoggingEvent> zapisi = uhvatiLog(env()
                .withProperty("str.eturizam.verifikacija.obavijesti.enabled", "true")
                .withProperty("str.eturizam.verifikacija.obavijesti.vrsta", "X".repeat(65))
                .withProperty("str.eturizam.verifikacija.obavijesti.tekst", "Objekt {objekt} nije verificiran."));

        assertThat(zapisi).filteredOn(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().startsWith("eturizam_verifikacija_config"))
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("obavijesti.vrsta ima 65"))
                .anyMatch(m -> m.contains("nema {rn}"))
                .noneMatch(m -> m.contains("objekt-tip"));
    }

    @Test
    @DisplayName("zurna verifikacija: zadane vrijednosti (prazni kljucevi) su ispravne")
    void verifikacija_defaultNotificationConfig_noError() {
        List<ILoggingEvent> zapisi = uhvatiLog(env()
                .withProperty("str.eturizam.verifikacija.obavijesti.enabled", "true")
                .withProperty("str.eturizam.verifikacija.obavijesti.vrsta", "")
                .withProperty("str.eturizam.verifikacija.obavijesti.tekst", ""));

        assertThat(zapisi).noneMatch(e -> e.getFormattedMessage().startsWith("eturizam_verifikacija_config"));
    }

    /** PostgreSQL baza na kojoj su sva prava osim {@code bezPrava} (te sonde vraćaju false). */
    private static List<ILoggingEvent> uhvatiLogPostgres(MockEnvironment env, String... bezPrava) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn("PostgreSQL");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenAnswer(inv ->
                !List.of(bezPrava).contains(inv.<String>getArgument(0)));
        return uhvati(env, jdbc);
    }

    private static List<ILoggingEvent> uhvatiLog(MockEnvironment env) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn("H2");
        return uhvati(env, jdbc);
    }

    private static List<ILoggingEvent> uhvati(MockEnvironment env, JdbcTemplate jdbc) {
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
