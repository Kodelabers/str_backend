package com.str.backend.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationNumberTest {

    @Test
    void rejectsMissingPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("1234567890123456789A"));
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR1200010000000000A"));   // 19 chars
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR1200010000000000ABC")); // 21 chars
    }

    @Test
    void rejectsNonHexChars() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR120001GGGGGGGGGGGG"));
    }

    @Test
    void acceptsValidFormat() {
        assertDoesNotThrow(() -> new RegistrationNumber("HR000000000000000000"));
        assertDoesNotThrow(() -> new RegistrationNumber("HR120001A3F8C2914D07"));
        assertDoesNotThrow(() -> new RegistrationNumber("HR12000100000000ABCD"));
    }

    @Test
    void generateProducesValidFormat() {
        RegistrationNumber rn = RegistrationNumber.generate(18, 0, 1);
        assertTrue(rn.getValue().matches("HR[0-9A-Fa-f]{18}"),
                "generated value must match HR + 18 hex digits, got: " + rn.getValue());
    }
}
