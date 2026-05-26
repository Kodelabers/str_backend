package com.str.backend.domain;

public enum SubmissionStatus {
    IN_PROCESSING,
    ACCEPTED,
    REJECTED;

    public boolean canTransitionTo(SubmissionStatus target, SubmissionTrigger trigger) {
        return switch (this) {
            case IN_PROCESSING -> switch (trigger) {
                case VALIDATION_PASSED -> target == ACCEPTED;
                case VALIDATION_REJECTED -> target == REJECTED;
            };
            case ACCEPTED, REJECTED -> false;
        };
    }

}
