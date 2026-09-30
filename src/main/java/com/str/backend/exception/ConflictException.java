package com.str.backend.exception;

/**
 * Zahtjev je valjan, ali se sukobljava s postojećim stanjem (409). {@code code} ide u
 * {@code details.code} odgovora — frontend na 409 prvo čita njega (v. DUPLICATE_LOCATION),
 * pa po njemu razlikuje sukobe bez parsiranja prevedene poruke.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String messageKey, String code) {
        super(messageKey);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
