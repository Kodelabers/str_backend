package com.str.backend.lessor;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

public class NotBreachedValidator implements ConstraintValidator<NotBreached, String> {

    private static final Set<String> BREACHED = Set.of(
            "123456", "123456789", "12345678", "1234567890", "1234567",
            "password", "password1", "password12", "password123", "password1234",
            "qwerty", "qwerty123", "qwertyuiop", "qwertyu", "1q2w3e4r5t",
            "111111", "1111111111", "12345", "000000", "654321",
            "iloveyou", "admin", "administrator", "welcome", "welcome1",
            "letmein", "letmein123", "monkey", "monkey123", "dragon",
            "sunshine", "princess", "football", "baseball", "superman",
            "michael", "shadow", "master", "killer", "trustno1",
            "abc123", "abc12345", "abcd1234", "abcdef", "abcdefg",
            "qazwsx", "qazwsxedc", "zaq12wsx", "asdfghjkl", "asdf1234",
            "passw0rd", "p@ssw0rd", "p@ssword", "passw0rd1", "passw0rd123",
            "lozinka", "lozinka123", "lozinka1234", "tajna", "tajnalozinka",
            "hrvatska", "hrvatska1", "zagreb", "split", "rijeka",
            "dinamo", "hajduk", "modric", "luka", "ivan",
            "marko", "marija", "ivana", "petar", "nikola",
            "qwerty1", "qwerty12", "qwerty1234", "1qaz2wsx", "1qaz2wsx3edc",
            "starwars", "freedom", "whatever", "hello", "hello123",
            "passwordpassword", "test1234", "test12345", "default", "defaultpassword",
            "changeme", "changeme123", "secret", "secret123", "secretpassword",
            "internet", "service", "computer", "michael1", "andrew",
            "jordan", "joshua", "anthony", "robert", "matthew",
            "samsung", "google", "facebook", "twitter", "instagram"
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return !BREACHED.contains(value.toLowerCase());
    }
}
