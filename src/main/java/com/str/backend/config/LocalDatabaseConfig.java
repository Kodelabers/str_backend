package com.str.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalDatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalDatabaseConfig.class);

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Bean
    @Primary
    public DataSource dataSource() {
        createDatabaseIfAbsent();
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    private void createDatabaseIfAbsent() {
        // Strip query params, extract DB name, build admin URL pointing to 'postgres' DB
        String cleanUrl = url.split("[?;]")[0];
        String dbName = cleanUrl.substring(cleanUrl.lastIndexOf('/') + 1);
        String adminUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf('/') + 1) + "postgres";

        try (Connection conn = DriverManager.getConnection(adminUrl, username, password)) {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM pg_database WHERE datname = ?")) {
                check.setString(1, dbName);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) {
                        try (Statement create = conn.createStatement()) {
                            create.execute("CREATE DATABASE \"" + dbName + "\"");
                        }
                        log.info("Created local database '{}'", dbName);
                    } else {
                        log.debug("Local database '{}' already exists", dbName);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to initialize local database '" + dbName + "': " + e.getMessage(), e);
        }
    }
}
