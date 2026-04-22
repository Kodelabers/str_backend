package com.str.backend.exception;

public class ValidationRejectedException extends RuntimeException {

    private final String step;

    public ValidationRejectedException(String step, String message) {
        super(message);
        this.step = step;
    }

    public String getStep() {
        return step;
    }
}
