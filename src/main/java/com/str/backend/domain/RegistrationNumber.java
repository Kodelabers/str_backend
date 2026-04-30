package com.str.backend.domain;

import lombok.Getter;

import java.security.SecureRandom;
import java.util.regex.Pattern;

@Getter
public class RegistrationNumber {

    private static final Pattern PATTERN = Pattern.compile("^HR\\d{8}$");
    private static final SecureRandom RNG = new SecureRandom();

    private final String value;

    public RegistrationNumber(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid registration number: " + value);
        }
        this.value = value;
    }

    public static RegistrationNumber generate() {
        int suffix = RNG.nextInt(100_000_000);
        return new RegistrationNumber("HR%08d".formatted(suffix));
    }
}
