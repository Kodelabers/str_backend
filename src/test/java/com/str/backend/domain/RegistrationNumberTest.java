package com.str.backend.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationNumberTest {

    @Test
    void rejectsMissingPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("123456789012345678901"));
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR12000100000000000"));   // 19 chars
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR1200010000000000000")); // 21 chars
    }

    @Test
    void rejectsNonDigitChars() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR120001A3F8C2914D07"));
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("HR120001GGGGGGGGGGGG"));
    }

    @Test
    void acceptsValidFormat() {
        assertDoesNotThrow(() -> new RegistrationNumber("HR000000000000000000"));
        assertDoesNotThrow(() -> new RegistrationNumber("HR120001839271650412"));
        assertDoesNotThrow(() -> new RegistrationNumber("HR180000123456789001"));
    }

    @Test
    void generateProducesValidFormat() {
        RegistrationNumber rn = RegistrationNumber.generate(18, 0, 1);
        assertTrue(rn.getValue().matches("HR\\d{18}"),
                "generated value must match HR + 18 decimal digits, got: " + rn.getValue());
    }
}
