package com.str.backend.egop.exception;

import lombok.Getter;

import java.util.Map;

@Getter
public class EgopBadRequestException extends EgopException {

    private Map<Integer, String> errors;

    public EgopBadRequestException(String message) {
        super(message);
    }

    public EgopBadRequestException(Map<Integer, String> errors) {
        super(String.join("; ", errors.values()));
        this.errors = errors;
    }

    public EgopBadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
