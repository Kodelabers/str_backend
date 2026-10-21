package com.str.backend.kp.client;

/**
 * Ishod poziva nije poznat: mreža, TLS, HTTP, SOAP fault ili nečitljiv odgovor. Za otpremu je
 * to uvijek prolazna greška — sljedeći pokušaj ide s istim {@code IdPoruke}.
 */
public class KpTransportException extends RuntimeException {

    public KpTransportException(String message) {
        super(message);
    }

    public KpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
