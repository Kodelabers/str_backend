package com.str.backend.domain;

public enum SubmissionStatus {
    INITIATED,
    IN_VERIFICATION,
    IN_PROCESSING,
    ACCEPTED,
    REJECTED;

    public boolean canTransitionTo(SubmissionStatus target, SubmissionTrigger trigger) {
        return switch (this) {
            case INITIATED -> switch (trigger) {
                case SUBMIT -> target == IN_PROCESSING;
                case FOREIGN_UPLOAD -> target == IN_VERIFICATION;
                default -> false;
            };
            case IN_VERIFICATION -> trigger == SubmissionTrigger.REFERENT_APPROVE && target == IN_PROCESSING;
            case IN_PROCESSING -> switch (trigger) {
                case VALIDATION_PASSED -> target == ACCEPTED;
                case VALIDATION_REJECTED -> target == REJECTED;
                default -> false;
            };
            case ACCEPTED, REJECTED -> false;
        };
    }

    public boolean isTerminal() {
        return this == ACCEPTED || this == REJECTED;
    }
}
