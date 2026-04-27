package com.str.backend.domain;

import lombok.Getter;

import java.security.SecureRandom;
import java.util.regex.Pattern;

@Getter
public class RegistracijskiBroj {

    private static final Pattern PATTERN = Pattern.compile("^HR\\d{8}$");
    private static final SecureRandom RNG = new SecureRandom();

    private final String value;

    public RegistracijskiBroj(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid registracijski broj: " + value);
        }
        this.value = value;
    }

    public static RegistracijskiBroj generate() {
        int suffix = RNG.nextInt(100_000_000);
        return new RegistracijskiBroj("HR%08d".formatted(suffix));
    }
}
