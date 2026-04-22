package com.str.backend.exception;

public class ExternalRegistryException extends RuntimeException {

    private final String registry;

    public ExternalRegistryException(String registry, String message) {
        super(message);
        this.registry = registry;
    }

    public ExternalRegistryException(String registry, String message, Throwable cause) {
        super(message, cause);
        this.registry = registry;
    }

    public String getRegistry() {
        return registry;
    }
}
