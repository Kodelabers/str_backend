package com.str.backend.exception;

/**
 * Korisnik je prijavljen i smije na endpoint, ali ovu radnju ne smije izvesti (403). Kao i
 * {@link ConflictException}, {@code code} ide u {@code details.code} odgovora, da frontend slučaj prepozna
 * bez parsiranja poruke.
 *
 * <p>Primjer: urudžbiranje očitovanja ili prigovora u županijskom postupku traži županijskog službenika
 * ({@code OBJECTION_NOT_COUNTY_OFFICIAL}).
 */
public class ForbiddenException extends RuntimeException {

    private final String code;

    public ForbiddenException(String messageKey, String code) {
        super(messageKey);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
