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
                case CONSENT_EXPIRY, INSPECTION, INCOMPLETE_DOCUMENTATION -> target == SUSPENDED;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            case SUSPENDED -> switch (trigger) {
                case REACTIVATE -> target == ACTIVE;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            // WITHDRAWN je terminalan: povlačenje/opoziv je trajno (čl. 6 STR Uredbe);
            // reaktivacija je moguća samo za suspendirane RB-ove (TC-STR-2.1-002, TC-STR-2.2-001).
            case WITHDRAWN -> false;
        };
    }

    public boolean isPubliclyVisible() {
        return this == ACTIVE;
    }
}
