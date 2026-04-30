package com.str.backend.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationNumberTest {

    @Test
    void rejectsMissingPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("12345678"));
    }

    @Test
    void rejectsWrongDigitCount() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR1234567"));
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR123456789"));
    }

    @Test
    void acceptsValidFormat() {
        assertDoesNotThrow(() -> new RegistrationNumber("HR00000000"));
        assertDoesNotThrow(() -> new RegistrationNumber("HR12345678"));
    }

    @Test
    void generateProducesValidFormat() {
        RegistrationNumber rn = RegistrationNumber.generate();
        assertTrue(rn.getValue().matches("HR\\d{8}"), "generated value must match HR + 8 digits");
    }
}
