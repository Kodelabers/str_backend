package com.str.backend.domain;

public enum RbStatus {
    U_OBRADI,
    AKTIVAN,
    SUSPENDIRAN,
    POVUCEN;

    public boolean canTransitionTo(RbStatus target, RbTrigger trigger) {
        return switch (this) {
            case U_OBRADI -> trigger == RbTrigger.ISSUE && target == AKTIVAN;
            case AKTIVAN -> switch (trigger) {
                case CONSENT_EXPIRY, INSPECTION -> target == SUSPENDIRAN;
                case WITHDRAWAL -> target == POVUCEN;
                default -> false;
            };
            case SUSPENDIRAN -> switch (trigger) {
                case REACTIVATE -> target == AKTIVAN;
                case WITHDRAWAL -> target == POVUCEN;
                default -> false;
            };
            case POVUCEN -> trigger == RbTrigger.REACTIVATE && target == AKTIVAN;
        };
    }

    public boolean isPubliclyVisible() {
        return this == AKTIVAN;
    }
}
