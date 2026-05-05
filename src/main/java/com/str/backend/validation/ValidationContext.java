package com.str.backend.validation;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;

public final class ValidationContext {

    private final AccommodationEntity accommodation;
    private final LessorEntity lessor;
    private volatile boolean requiresCoOwnerConsent;

    public ValidationContext(AccommodationEntity accommodation, LessorEntity lessor) {
        this.accommodation = accommodation;
        this.lessor = lessor;
    }

    public AccommodationEntity accommodation() { return accommodation; }
    public LessorEntity lessor() { return lessor; }
    public boolean requiresCoOwnerConsent() { return requiresCoOwnerConsent; }

    public void markCoOwnerConsentRequired() { this.requiresCoOwnerConsent = true; }
}
