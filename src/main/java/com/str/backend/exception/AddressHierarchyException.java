package com.str.backend.exception;

/**
 * Adresa iz zahtjeva nije povezan lanac adresnog registra (županija → grad/općina → naselje →
 * ulica → kućni broj) ili joj nedostaje razina koju lanac traži — 400.
 *
 * <p>{@code details.code} je {@link Code#name()}, a {@code details.field} naziv polja u tijelu
 * zahtjeva na kojem se lanac prekida (npr. {@code cityId}, {@code podnositelj.naseljeId}), da
 * frontend može označiti polje bez parsiranja prevedene poruke. V. {@code AddressHierarchyValidator}.
 */
public class AddressHierarchyException extends RuntimeException {

    public enum Code {
        /** Razine postoje, ali ne pripadaju jedna drugoj (ili id ne postoji u registru). */
        ADDRESS_INCONSISTENT("error.address.inconsistent"),
        /** Nedostaje razina koju lanac traži (id ili, bez id-a, upisani tekst). */
        ADDRESS_INCOMPLETE("error.address.incomplete");

        private final String messageKey;

        Code(String messageKey) {
            this.messageKey = messageKey;
        }
    }

    private final Code code;
    private final String field;

    private AddressHierarchyException(Code code, String field) {
        super(code.messageKey);
        this.code = code;
        this.field = field;
    }

    public static AddressHierarchyException inconsistent(String field) {
        return new AddressHierarchyException(Code.ADDRESS_INCONSISTENT, field);
    }

    public static AddressHierarchyException incomplete(String field) {
        return new AddressHierarchyException(Code.ADDRESS_INCOMPLETE, field);
    }

    public Code getCode() {
        return code;
    }

    public String getField() {
        return field;
    }
}
