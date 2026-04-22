package com.str.backend.domain;

public enum Status {
    INICIIRAN,
    VALIDACIJA,
    U_OBRADI,
    AKTIVAN,
    SUSPENDIRAN,
    POVUCEN;

    public boolean canTransitionTo(Status target, TransitionTrigger trigger) {
        return switch (this) {
            case INICIIRAN -> target == VALIDACIJA && trigger == TransitionTrigger.USER_SUBMIT;
            case VALIDACIJA -> switch (trigger) {
                case VALIDATION_PASSED -> target == AKTIVAN;
                case AWAITING_CALLBACK -> target == U_OBRADI;
                default -> false;
            };
            case U_OBRADI -> target == AKTIVAN && trigger == TransitionTrigger.CALLBACK_CONFIRMED;
            case AKTIVAN -> switch (trigger) {
                case CONSENT_EXPIRY, INSPECTION -> target == SUSPENDIRAN;
                case WITHDRAWAL -> target == POVUCEN;
                default -> false;
            };
            case SUSPENDIRAN -> switch (trigger) {
                case REACTIVATION_CYCLE -> target == VALIDACIJA;
                case WITHDRAWAL -> target == POVUCEN;
                default -> false;
            };
            case POVUCEN -> false;
        };
    }

    public boolean isPubliclyVisible() {
        return this == AKTIVAN;
    }
}
