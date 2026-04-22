package com.str.backend.domain;

public enum TransitionTrigger {
    USER_SUBMIT,
    VALIDATION_PASSED,
    AWAITING_CALLBACK,
    CALLBACK_CONFIRMED,
    CONSENT_EXPIRY,
    INSPECTION,
    WITHDRAWAL,
    REACTIVATION_CYCLE
}
