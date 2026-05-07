package com.str.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDatabaseConfigTest {

    private String extractDatabaseName(String url) throws Exception {
        Method m = LocalDatabaseConfig.class.getDeclaredMethod("extractDatabaseName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, url);
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:postgresql://localhost:5432/str_db_local, str_db_local",
        "jdbc:postgresql://localhost:5432/str_db_local?currentSchema=str_rn, str_db_local",
        "jdbc:postgresql://host:5432/mydb, mydb",
        "jdbc:postgresql://host/mydb, mydb"
    })
    void extractDatabaseName_parsesCorrectly(String url, String expected) throws Exception {
        assertThat(extractDatabaseName(url)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"jdbc:postgresql", "noslash"})
    void extractDatabaseName_returnsNullForUnparseable(String url) throws Exception {
        assertThat(extractDatabaseName(url)).isNull();
    }

    @Test
    void extractDatabaseName_emptyTailAfterSlashReturnsEmpty() throws Exception {
        assertThat(extractDatabaseName("jdbc:postgresql://localhost:5432/")).isEqualTo("");
    }

    @Test
    void requireSafeIdentifier_rejectsQuoteInjection() throws Exception {
        Method m = LocalDatabaseConfig.class.getDeclaredMethod("requireSafeIdentifier", String.class);
        m.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> {
                    try {
                        m.invoke(null, "db\"; DROP DATABASE postgres; --");
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void requireSafeIdentifier_acceptsValidIdentifiers() throws Exception {
        Method m = LocalDatabaseConfig.class.getDeclaredMethod("requireSafeIdentifier", String.class);
        m.setAccessible(true);
        m.invoke(null, "str_db_local");
        m.invoke(null, "str_rn");
        m.invoke(null, "mySchema");
    }
}
