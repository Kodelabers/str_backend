package com.str.backend.domain;

public enum RnStatus {
    IN_PROCESSING,
    ACTIVE,
    SUSPENDED,
    WITHDRAWN;

    public boolean canTransitionTo(RnStatus target, RnTrigger trigger) {
        return switch (this) {
            case IN_PROCESSING -> trigger == RnTrigger.ISSUE && target == ACTIVE;
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
            case WITHDRAWN -> trigger == RnTrigger.REACTIVATE && target == ACTIVE;
        };
    }

    public boolean isPubliclyVisible() {
        return this == ACTIVE;
    }
}
