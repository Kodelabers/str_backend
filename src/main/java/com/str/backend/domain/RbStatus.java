package com.str.backend.domain;

public enum RbStatus {
    IN_PROCESSING,
    ACTIVE,
    SUSPENDED,
    WITHDRAWN;

    public boolean canTransitionTo(RbStatus target, RbTrigger trigger) {
        return switch (this) {
            case IN_PROCESSING -> trigger == RbTrigger.ISSUE && target == ACTIVE;
            case ACTIVE -> switch (trigger) {
                case CONSENT_EXPIRY, INSPECTION -> target == SUSPENDED;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            case SUSPENDED -> switch (trigger) {
                case REACTIVATE -> target == ACTIVE;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            case WITHDRAWN -> trigger == RbTrigger.REACTIVATE && target == ACTIVE;
        };
    }

    public boolean isPubliclyVisible() {
        return this == ACTIVE;
    }
}
