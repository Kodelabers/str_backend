package com.str.backend.validation;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.core.CoreObjektEntity;
import com.str.backend.lessor.LessorEntity;

public final class ValidationContext {

    private final AccommodationEntity accommodation;
    private final LessorEntity lessor;
    private final CoreObjektEntity coreObject;
    private boolean requiresCoOwnerConsent;

    public ValidationContext(AccommodationEntity accommodation, LessorEntity lessor, CoreObjektEntity coreObject) {
        this.accommodation = accommodation;
        this.lessor = lessor;
        this.coreObject = coreObject;
    }

    public AccommodationEntity accommodation() { return accommodation; }
    public LessorEntity lessor() { return lessor; }
    public CoreObjektEntity coreObject() { return coreObject; }
    public boolean requiresCoOwnerConsent() { return requiresCoOwnerConsent; }

    public void markCoOwnerConsentRequired() { this.requiresCoOwnerConsent = true; }
}
