package com.str.backend.attachment;

import org.springframework.http.HttpStatus;

/**
 * Odbijen prilog. {@code code} ide u {@code details.code} odgovora (kao kod prigovora i rješenja),
 * pa frontend razlikuje slučajeve bez parsiranja prevedene poruke; poruka je ključ iz
 * {@code messages*.properties}.
 */
public class PrilogException extends RuntimeException {

    /** Prazna datoteka (400). */
    public static final String EMPTY = "ATTACHMENT_EMPTY";
    /** Datoteka veća od dopuštenog (413) — inače je odbija već multipart limit, bez koda. */
    public static final String TOO_LARGE = "ATTACHMENT_TOO_LARGE";
    /** Sadržaj nije PDF, JPG, PNG, DOC ni DOCX (415). */
    public static final String TYPE = "ATTACHMENT_TYPE";
    /** Previše nevezanih priloga istog učitavatelja (409) — staging nije pohrana. */
    public static final String STAGED_LIMIT = "ATTACHMENT_STAGED_LIMIT";
    /** Više od dopuštenog broja priloga uz jedno pismeno (400). */
    public static final String COUNT = "ATTACHMENT_COUNT";
    /**
     * Prilozi službenika uz jednu radnju zajedno su veći od dopuštenog (400). Non-EU iznajmljivaču
     * je e-pošta pravna dostava akta, pa svi prilozi moraju stati u jednu poruku.
     */
    public static final String TOTAL_TOO_LARGE = "ATTACHMENT_TOTAL_TOO_LARGE";
    /** Prilog ne postoji — npr. obrisan noćnim čišćenjem nevezanih (404). */
    public static final String NOT_FOUND = "ATTACHMENT_NOT_FOUND";
    /** Prilog je učitao netko drugi (403). */
    public static final String FORBIDDEN = "ATTACHMENT_FORBIDDEN";
    /** Prilog je već vezan uz pismeno (409). */
    public static final String ALREADY_BOUND = "ATTACHMENT_ALREADY_BOUND";

    private final HttpStatus status;
    private final String code;

    public PrilogException(HttpStatus status, String messageKey, String code) {
        super(messageKey);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
