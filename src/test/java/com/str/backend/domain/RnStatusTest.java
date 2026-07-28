package com.str.backend.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The suspension triggers are listed twice — {@code RnService#suspend} validates the request,
 * {@link RnStatus#canTransitionTo} validates the transition — and the two lists drifted once:
 * {@code OTHER} passed the service and was then refused by the state machine, so a proposal with
 * a free-text reason failed with 409 after the request had already been accepted as valid. These
 * tests pin the two lists to each other.
 */
class RnStatusTest {

    /** Exactly the set {@code RnService#suspend} lets through. */
    @ParameterizedTest
    @EnumSource(value = RnTrigger.class,
            names = {"CONSENT_EXPIRY", "INSPECTION", "INCOMPLETE_DOCUMENTATION", "OTHER"})
    void everySuspendTriggerProposesSuspensionFromActive(RnTrigger trigger) {
        assertThat(RnStatus.ACTIVE.canTransitionTo(RnStatus.SUSPENSION_PROPOSED, trigger)).isTrue();
    }

    /** A suspension reason must never jump the response phase (čl. 30. st. 2 ZUP). */
    @ParameterizedTest
    @EnumSource(value = RnTrigger.class,
            names = {"CONSENT_EXPIRY", "INSPECTION", "INCOMPLETE_DOCUMENTATION", "OTHER"})
    void suspendTriggersDoNotSuspendDirectly(RnTrigger trigger) {
        assertThat(RnStatus.ACTIVE.canTransitionTo(RnStatus.SUSPENDED, trigger)).isFalse();
    }

    @Test
    void onlyTheDeadlineJobTurnsAProposalIntoASuspension() {
        assertThat(RnStatus.SUSPENSION_PROPOSED.canTransitionTo(RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED))
                .isTrue();
        assertThat(RnStatus.SUSPENSION_PROPOSED.canTransitionTo(RnStatus.SUSPENDED, RnTrigger.OTHER)).isFalse();
    }

    /** Withdrawal is permanent (čl. 6 STR Uredbe) — no trigger leads back out. */
    @ParameterizedTest
    @EnumSource(RnTrigger.class)
    void withdrawnIsTerminal(RnTrigger trigger) {
        for (RnStatus target : RnStatus.values()) {
            assertThat(RnStatus.WITHDRAWN.canTransitionTo(target, trigger)).isFalse();
        }
    }
}
