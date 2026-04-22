package com.str.backend.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusTransitionTest {

    @Test
    void iniciiranMoveToValidacijaOnUserSubmit() {
        assertTrue(Status.INICIIRAN.canTransitionTo(Status.VALIDACIJA, TransitionTrigger.USER_SUBMIT));
    }

    @Test
    void validacijaMoveToAktivanOnValidationPassed() {
        assertTrue(Status.VALIDACIJA.canTransitionTo(Status.AKTIVAN, TransitionTrigger.VALIDATION_PASSED));
    }

    @Test
    void validacijaMoveToUObradiOnAwaitingCallback() {
        assertTrue(Status.VALIDACIJA.canTransitionTo(Status.U_OBRADI, TransitionTrigger.AWAITING_CALLBACK));
    }

    @Test
    void uObradiMoveToAktivanOnCallbackConfirmed() {
        assertTrue(Status.U_OBRADI.canTransitionTo(Status.AKTIVAN, TransitionTrigger.CALLBACK_CONFIRMED));
    }

    @Test
    void aktivanCanBeSuspendedOnConsentExpiry() {
        assertTrue(Status.AKTIVAN.canTransitionTo(Status.SUSPENDIRAN, TransitionTrigger.CONSENT_EXPIRY));
    }

    @Test
    void aktivanCanBeWithdrawn() {
        assertTrue(Status.AKTIVAN.canTransitionTo(Status.POVUCEN, TransitionTrigger.WITHDRAWAL));
    }

    @Test
    void suspendiranCannotGoDirectlyToAktivan() {
        assertFalse(Status.SUSPENDIRAN.canTransitionTo(Status.AKTIVAN, TransitionTrigger.VALIDATION_PASSED));
        assertFalse(Status.SUSPENDIRAN.canTransitionTo(Status.AKTIVAN, TransitionTrigger.CALLBACK_CONFIRMED));
    }

    @Test
    void suspendiranReactivatesThroughValidacija() {
        assertTrue(Status.SUSPENDIRAN.canTransitionTo(Status.VALIDACIJA, TransitionTrigger.REACTIVATION_CYCLE));
    }

    @Test
    void povucenIsTerminal() {
        for (Status target : Status.values()) {
            for (TransitionTrigger trigger : TransitionTrigger.values()) {
                assertFalse(Status.POVUCEN.canTransitionTo(target, trigger),
                        "POVUCEN must not allow transition to " + target + " via " + trigger);
            }
        }
    }

    @Test
    void onlyAktivanIsPubliclyVisible() {
        for (Status s : Status.values()) {
            assertTrue((s == Status.AKTIVAN) == s.isPubliclyVisible(),
                    "public visibility mismatch for " + s);
        }
    }
}
