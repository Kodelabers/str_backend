package com.str.backend.domain;

import lombok.Getter;

import java.security.SecureRandom;
import java.util.regex.Pattern;

@Getter
public class RegistrationNumber {

    public static final String REGEXP = "^HR[0-9A-Fa-f]{18}$";
    private static final Pattern PATTERN = Pattern.compile(REGEXP);
    private static final SecureRandom RNG = new SecureRandom();

    private final String value;

    public RegistrationNumber(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid registration number: " + value);
        }
        this.value = value;
    }

    /**
     * HR + CC (county, 2 hex) + GG (group, 2 hex) + TT (type, 2 hex) + 12 random hex digits.
     * Example: HR120001A3F8C2914D07
     */
    public static RegistrationNumber generate(int countyCode, int groupCode, int typeCode) {
        long uniqueness = (RNG.nextLong() & 0x0000_FFFF_FFFF_FFFFL);
        return new RegistrationNumber(
                "HR%02X%02X%02X%012X".formatted(countyCode, groupCode, typeCode, uniqueness));
    }
}
