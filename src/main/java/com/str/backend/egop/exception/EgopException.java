package com.str.backend.egop.exception;

public class EgopException extends Exception {

    public EgopException(String message) {
        super(message);
    }

    public EgopException(String message, Throwable cause) {
        super(message, cause);
    }
}
