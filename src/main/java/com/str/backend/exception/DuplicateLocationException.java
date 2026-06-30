package com.str.backend.exception;

/**
 * Soft-warn signal raised when the lessor already has an ACTIVE or SUSPENDED RN
 * for the same house_number_code. Surfaces as 409 with the existing RN so the
 * frontend can prompt for explicit confirmation; resubmitting with
 * {@code confirmDuplicateLocation=true} bypasses the check.
 */
public class DuplicateLocationException extends RuntimeException {

    private final String existingRegistrationNumber;

    public DuplicateLocationException(String existingRegistrationNumber) {
        super("error.registration.duplicate.location");
        this.existingRegistrationNumber = existingRegistrationNumber;
    }

    public String getExistingRegistrationNumber() {
        return existingRegistrationNumber;
    }
}
