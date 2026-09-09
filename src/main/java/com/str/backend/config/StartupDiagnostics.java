package com.str.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Jedan blok u logu nakon dizanja konteksta: efektivna konfiguracija + stanje prava na bazi.
 *
 * <p>Postoji zbog jedne konkretne klase problema — konfiguracije koja se <b>ne vidi na startupu</b>,
 * nego prvi put pukne kad korisnik otvori formular. Adresni registri ({@code rpj_dgu},
 * {@code eturizam_test}) su u vlasništvu druge role; nemamo li na njima {@code USAGE}, aplikacija
 * se digne uredno i tek padnu padajući izbornici za županiju/naselje/ulicu. Bez ovoga se to otkrije
 * kao prijavljeni bug, a ne kao redak u logu.
 *
 * <p><b>Nikad ne obara start.</b> Svaka sonda je zasebno ograđena; greška se logira i ide se dalje.
 * Dijagnostika ne smije biti razlog da servis ne krene.
 *
 * <p>Ne ispisuje tajne — samo imena, putanje i izvedene zastavice. Lozinke, HMAC ključ i ključ za
 * šifriranje skica se ne logiraju.
 */
@Component
@Profile("!test")
public class StartupDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    /** Sonde: shema, jedna tablica koju kod stvarno čita, i što se lomi ako je nedostupna. */
    private static final List<SchemaProbe> PROBES = List.of(
            new SchemaProbe("str_rn", "registration_number", "naša shema — registri, akti, sjednice"),
            new SchemaProbe("str", "facility", "popis eTurizam objekata (NIAS dashboard)"),
            new SchemaProbe("str", "subject", "OIB lookup iznajmljivača"),
            new SchemaProbe("str", "country", "padajući izbornik država"),
            new SchemaProbe("rpj_dgu", "zupanije", "adresna kaskada u registracijskom formularu"),
            new SchemaProbe("eturizam_test", "ar_ulice", "ulice i kućni brojevi u formularu"));

    private final Environment env;
    private final JdbcTemplate jdbc;

    public StartupDiagnostics(Environment env, JdbcTemplate jdbc) {
        this.env = env;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        try {
            logConfig();
        } catch (RuntimeException e) {
            log.warn("startup_config_report_failed — dijagnostika preskočena, servis radi normalno", e);
        }
        try {
            logDatabase();
        } catch (RuntimeException e) {
            log.warn("startup_db_report_failed — dijagnostika preskočena, servis radi normalno", e);
        }
    }

    private void logConfig() {
        log.info("startup_config profili={} datasource={} user={} liquibase_schema={} liquibase_contexts={}",
                List.of(env.getActiveProfiles()),
                env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.username"),
                env.getProperty("spring.liquibase.default-schema"),
                env.getProperty("spring.liquibase.contexts"));

        if (env.getProperty("nias.saml.enabled", Boolean.class, false)) {
            log.info("startup_nias enabled=true metadata={} entity_id={} keystore={} alias={}",
                    env.getProperty("nias.saml.metadata-uri"),
                    env.getProperty("nias.saml.entity-id"),
                    env.getProperty("nias.saml.keystore-path"),
                    env.getProperty("nias.saml.key-alias"));
            log.info("startup_nias_urls acs={} slo={} success={} failure={} logout={}",
                    env.getProperty("nias.saml.acs-url"),
                    env.getProperty("nias.saml.slo-url"),
                    env.getProperty("nias.saml.success-redirect-url"),
                    env.getProperty("nias.saml.failure-redirect-url"),
                    env.getProperty("nias.saml.logout-redirect-url"));
        } else {
            log.info("startup_nias enabled=false — SAML prijava je ugašena, /api/nias/** i "
                    + "generateRegistrationNumber padaju pod permitAll");
        }

        boolean egopEnabled = env.getProperty("hr.infodom.str.integration.egop.enabled", Boolean.class, false);
        log.info("startup_egop enabled={} base_url={}{}",
                egopEnabled,
                env.getProperty("hr.infodom.str.integration.egop.base-url"),
                egopEnabled ? "" : " (EgopClientMock — KLASA/URBROJ dobivaju prefiks MOCK-)");

        boolean mailEnabled = env.getProperty("app.mail.enabled", Boolean.class, false);
        log.info("startup_mail enabled={} host={} from={}{}",
                mailEnabled,
                env.getProperty("spring.mail.host"),
                env.getProperty("app.mail.from"),
                mailEnabled ? " — POZOR: poruke stvarno izlaze" : " (LoggingEmailService, ništa ne izlazi)");

        log.info("startup_captcha enabled={} hmac_key={}",
                env.getProperty("app.captcha.enabled", Boolean.class, false),
                describeSecret(env.getProperty("app.captcha.hmac-key"), "change-me-in-production"));

        log.info("startup_web frontend_base={} cors={}",
                env.getProperty("app.frontend.base-url"),
                env.getProperty("app.cors.allowed-origins"));
    }

    private void logDatabase() {
        String product = jdbc.execute((ConnectionCallback<String>) c ->
                c.getMetaData().getDatabaseProductName());
        if (product == null || !product.toLowerCase().contains("postgres")) {
            log.info("startup_db product={} — sonde prava su PostgreSQL-specificne, preskacem", product);
            return;
        }

        // session_user je prijavljeni korisnik, current_user efektivna rola nakon SET ROLE iz
        // `options=-c role=...` u JDBC URL-u. Razlika je namjerna i mora se vidjeti u logu.
        // queryForObject(String, RowMapper) — jdbc.query(String, lambda) je dvosmislen
        // (ResultSetExtractor vs RowCallbackHandler).
        String[] identitet = jdbc.queryForObject("select current_user, session_user",
                (rs, rowNum) -> new String[]{rs.getString(1), rs.getString(2)});
        if (identitet != null) {
            log.info("startup_db current_user={} session_user={}", identitet[0], identitet[1]);
        }

        PROBES.forEach(this::probeOne);

        // Odrediste FacilityRegistrationNumberWriteBack-a. Bez UPDATE prava RB se svejedno izda,
        // ali se ne upise natrag u eTurizam.
        Boolean canWrite = queryBoolean("select has_table_privilege('str.facility','UPDATE')");
        if (Boolean.FALSE.equals(canWrite)) {
            log.warn("startup_schema_writeback str.facility UPDATE=false — RB se NE upisuje natrag u "
                    + "eTurizam (facility_writeback_failed u logu); RB ostaje valjan, ali tuStart "
                    + "handoff se ne moze testirati end-to-end");
        } else if (Boolean.TRUE.equals(canWrite)) {
            log.info("startup_schema_writeback str.facility UPDATE=true");
        }
    }

    private void probeOne(SchemaProbe probe) {
        Boolean usage = queryBoolean("select has_schema_privilege('" + probe.schema() + "','USAGE')");
        if (usage == null) {
            log.warn("startup_schema_unknown shema={} — provjera prava nije prosla", probe.schema());
            return;
        }
        if (!usage) {
            log.error("startup_schema_missing shema={} — NEMAMO USAGE. Ne radi: {}. Rjesenje je GRANT "
                            + "od vlasnika sheme, ne izmjena nase konfiguracije (DEPLOY-PREPROD.md 2d).",
                    probe.schema(), probe.brokenIfMissing());
            return;
        }
        // USAGE na shemi ne implicira SELECT na tablice, pa se cita i sama tablica.
        String qualified = probe.schema() + "." + probe.table();
        try {
            Integer n = jdbc.queryForObject("select count(*) from " + qualified, Integer.class);
            log.info("startup_schema_ok tablica={} redova={}", qualified, n);
        } catch (RuntimeException e) {
            log.error("startup_schema_unreadable tablica={} — shema je dostupna, ali tablica nije "
                            + "(nema SELECT ili tablica ne postoji). Ne radi: {}. Uzrok: {}",
                    qualified, probe.brokenIfMissing(), e.getMessage());
        }
    }

    private Boolean queryBoolean(String sql) {
        try {
            return jdbc.queryForObject(sql, Boolean.class);
        } catch (RuntimeException e) {
            log.debug("sonda nije prosla: {}", sql, e);
            return null;
        }
    }

    /** Stanje tajne bez ispisa vrijednosti. */
    private static String describeSecret(String value, String builtInDefault) {
        if (value == null) {
            return "NEPOSTAVLJEN";
        }
        if (value.isBlank()) {
            return "PRAZAN (pregazio je Spring default — provjeri .env)";
        }
        if (value.equals(builtInDefault)) {
            return "UGRADENI DEFAULT (nije override-an)";
        }
        return "postavljen";
    }

    private record SchemaProbe(String schema, String table, String brokenIfMissing) {
    }
}
