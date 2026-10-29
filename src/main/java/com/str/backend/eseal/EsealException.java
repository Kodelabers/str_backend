package com.str.backend.eseal;

/**
 * Pečatiranje nije uspjelo. {@code errorCode} je stabilni kod servisa (RFC 9457 tijelo) ili naš
 * za ishode bez takvog tijela:
 * <ul>
 *   <li>{@code TRANSPORT} — mreža, TLS ili timeout; servis je možda ipak pečatirao, pa retry s istim
 *       ključem i istim bajtovima dobiva spremljeni ishod;</li>
 *   <li>{@code HTTP_<status>} — odgovor bez problem+json tijela (npr. 502/504 proxyja);</li>
 *   <li>{@code RESPONSE_INVALID}, {@code DIGEST_MISSING}, {@code DIGEST_MISMATCH} — odgovor 2xx koji
 *       ne smijemo prihvatiti kao pečaćeni akt;</li>
 *   <li>{@code CLIENT_ERROR} — greška na našoj strani oko poziva.</li>
 * </ul>
 *
 * <p>Poruka nikad ne sadrži sadržaj akta ni kontrolni broj — završava u logu i u
 * {@code egop_pismeno.sync_error}.
 */
public class EsealException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;
    private final String traceId;
    private final boolean retryable;

    public EsealException(int httpStatus, String errorCode, String traceId, boolean retryable,
                          String detail, Throwable cause) {
        super(describe(httpStatus, errorCode, traceId, retryable, detail), cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.traceId = traceId;
        this.retryable = retryable;
    }

    public EsealException(int httpStatus, String errorCode, String traceId, boolean retryable, String detail) {
        this(httpStatus, errorCode, traceId, retryable, detail, null);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public String traceId() {
        return traceId;
    }

    public boolean retryable() {
        return retryable;
    }

    private static String describe(int httpStatus, String errorCode, String traceId, boolean retryable,
                                   String detail) {
        StringBuilder sb = new StringBuilder("eseal status=").append(httpStatus)
                .append(" error_code=").append(errorCode)
                .append(" retryable=").append(retryable)
                .append(" trace_id=").append(traceId == null ? "-" : traceId);
        if (detail != null && !detail.isBlank()) {
            sb.append(" — ").append(detail);
        }
        return sb.toString();
    }
}
