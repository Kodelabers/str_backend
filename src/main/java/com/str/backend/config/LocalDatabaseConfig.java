package com.str.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ensures required schemas exist before Liquibase runs.
 *
 * local  — also auto-creates the database itself (dev laptop, no pre-existing DB)
 * mock   — DB already provisioned by Railway; only creates the str_rn schema
 *
 * Uses ApplicationPreparedEvent so it fires after the environment is resolved
 * but before any beans (DataSource, SpringLiquibase) are instantiated.
 */
public class LocalDatabaseConfig implements ApplicationListener<ApplicationPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(LocalDatabaseConfig.class);
    private static final String MAINTENANCE_DB = "postgres";
    private static final Pattern PG_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        List<String> profiles = List.of(env.getActiveProfiles());
        boolean isLocal = profiles.contains("local");
        boolean isMock = profiles.contains("mock");
        if (!isLocal && !isMock) {
            return;
        }

        String url = env.getProperty("spring.datasource.url");
        String user = env.getProperty("spring.datasource.username");
        String pwd = env.getProperty("spring.datasource.password");

        if (isLocal) {
            String adminUrl = env.getProperty("str.local.admin-url",
                    "jdbc:postgresql://localhost:5432/" + MAINTENANCE_DB);
            String targetDb = extractDatabaseName(url);
            if (targetDb == null) {
                log.warn("Cannot parse database name from {}, skipping DB auto-create", url);
            } else {
                requireSafeIdentifier(targetDb);
                ensureDatabase(adminUrl, user, pwd, targetDb);
            }
        }

        // Liquibase writes its changelog table into spring.liquibase.default-schema (str_rn).
        // That schema must exist before Liquibase initialises — create it here for both profiles.
        String liquibaseSchema = env.getProperty("spring.liquibase.default-schema");
        if (liquibaseSchema != null && !liquibaseSchema.isBlank()) {
            requireSafeIdentifier(liquibaseSchema);
            ensureSchema(url, user, pwd, liquibaseSchema);
        }
    }

    private static void ensureDatabase(String adminUrl, String user, String pwd, String targetDb) {
        try (Connection c = DriverManager.getConnection(adminUrl, user, pwd)) {
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM pg_database WHERE datname = ?")) {
                ps.setString(1, targetDb);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (!exists) {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("CREATE DATABASE \"" + targetDb + "\"");
                    log.info("local DB '{}' created", targetDb);
                }
            } else {
                log.info("local DB '{}' already exists", targetDb);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to ensure local database '" + targetDb + "' exists", e);
        }
    }

    private static void ensureSchema(String url, String user, String pwd, String schema) {
        try (Connection c = DriverManager.getConnection(url, user, pwd);
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            log.info("schema '{}' ensured", schema);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to ensure schema '" + schema + "' exists", e);
        }
    }

    private static void requireSafeIdentifier(String name) {
        if (!PG_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalStateException(
                    "Refusing unsafe PostgreSQL identifier: '" + name + "'");
        }
    }

    private static String extractDatabaseName(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        int slash = jdbcUrl.lastIndexOf('/');
        if (slash < 0) return null;
        String tail = jdbcUrl.substring(slash + 1);
        int qmark = tail.indexOf('?');
        return qmark < 0 ? tail : tail.substring(0, qmark);
    }
}
