package com.str.backend.exception;

/**
 * Traženo je postojalo, ali više nije dostupno (410). Kao i {@link ConflictException}, {@code code}
 * ide u {@code details.code} odgovora, da frontend slučaj prepozna bez parsiranja poruke.
 *
 * <p>Primjer: ponovni zahtjev za RB nakon opoziva kad je istekao rok za preuzimanje podataka
 * ({@code REISSUE_EXPIRED}).
 */
public class GoneException extends RuntimeException {

    private final String code;

    public GoneException(String messageKey, String code) {
        super(messageKey);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
