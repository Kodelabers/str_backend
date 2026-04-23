package com.str.backend.exception;

public class IllegalStatusTransitionException extends RuntimeException {

    public IllegalStatusTransitionException(String message) {
        super(message);
    }
}
