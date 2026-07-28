package com.str.backend.domain;

public enum RnStatus {
    IN_PROCESSING,
    ACTIVE,
    SUSPENSION_PROPOSED,
    SUSPENDED,
    WITHDRAWN;

    public boolean canTransitionTo(RnStatus target, RnTrigger trigger) {
        return switch (this) {
            case IN_PROCESSING -> trigger == RnTrigger.ISSUE && target == ACTIVE;
            case ACTIVE -> switch (trigger) {
                // OTHER je slobodan razlog: RnService#suspend uz njega traži obrazloženje, koje
                // onda nosi izreku prijedloga umjesto šifriranog razloga.
                case CONSENT_EXPIRY, INSPECTION, INCOMPLETE_DOCUMENTATION, OTHER -> target == SUSPENSION_PROPOSED;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            case SUSPENSION_PROPOSED -> switch (trigger) {
                case REVOKE_PROPOSAL -> target == ACTIVE;
                case DEADLINE_EXCEEDED -> target == SUSPENDED;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            case SUSPENDED -> switch (trigger) {
                case REACTIVATE -> target == ACTIVE;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            // WITHDRAWN je terminalan: povlačenje/opoziv je trajno (čl. 6 STR Uredbe).
            case WITHDRAWN -> false;
        };
    }

    public boolean isPubliclyVisible() {
        return this == ACTIVE;
    }
}
