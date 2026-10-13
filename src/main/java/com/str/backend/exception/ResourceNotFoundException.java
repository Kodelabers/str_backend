package com.str.backend.exception;

public class ResourceNotFoundException extends RuntimeException {

    /** Neobavezan kod za {@code details.code} (npr. {@code REISSUE_NOT_FOUND}); {@code null} inače. */
    private final String code;

    public ResourceNotFoundException(String message) {
        this(message, null);
    }

    public ResourceNotFoundException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
