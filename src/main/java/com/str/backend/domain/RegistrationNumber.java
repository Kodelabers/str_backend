package com.str.backend.domain;

import lombok.Getter;

import java.security.SecureRandom;
import java.util.regex.Pattern;

@Getter
public class RegistrationNumber {

    public static final String REGEXP = "^HR\\d{18}$";
    private static final Pattern PATTERN = Pattern.compile(REGEXP);
    private static final SecureRandom RNG = new SecureRandom();
    private static final long UNIQUENESS_BOUND = 1_000_000_000_000L;

    private final String value;

    public RegistrationNumber(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid registration number: " + value);
        }
        this.value = value;
    }

    /**
     * HR + CC (county, 2 digits) + GG (group, 2 digits) + TT (type, 2 digits) + 12 random digits.
     * Example: HR120001839271650412
     */
    public static RegistrationNumber generate(int countyCode, int groupCode, int typeCode) {
        long uniqueness = (RNG.nextLong() & Long.MAX_VALUE) % UNIQUENESS_BOUND;
        return new RegistrationNumber(
                "HR%02d%02d%02d%012d".formatted(countyCode, groupCode, typeCode, uniqueness));
    }
}
