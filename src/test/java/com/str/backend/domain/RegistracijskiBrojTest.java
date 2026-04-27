package com.str.backend.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistracijskiBrojTest {

    @Test
    void rejectsMissingPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new RegistracijskiBroj("12345678"));
    }

    @Test
    void rejectsWrongDigitCount() {
        assertThrows(IllegalArgumentException.class, () -> new RegistracijskiBroj("HR1234567"));
        assertThrows(IllegalArgumentException.class, () -> new RegistracijskiBroj("HR123456789"));
    }

    @Test
    void acceptsValidFormat() {
        assertDoesNotThrow(() -> new RegistracijskiBroj("HR00000000"));
        assertDoesNotThrow(() -> new RegistracijskiBroj("HR12345678"));
    }

    @Test
    void generateProducesValidFormat() {
        RegistracijskiBroj rb = RegistracijskiBroj.generate();
        assertTrue(rb.getValue().matches("HR\\d{8}"), "generated value must match HR + 8 digits");
    }
}
