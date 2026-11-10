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
            names = {"NO_DECISION", "DECISION_REVOKED", "INCOMPLETE_DOCUMENTATION", "INSPECTION", "OTHER"})
    void everySuspendTriggerProposesSuspensionFromActive(RnTrigger trigger) {
        assertThat(RnStatus.ACTIVE.canTransitionTo(RnStatus.SUSPENSION_PROPOSED, trigger)).isTrue();
    }

    /** The state machine and {@code RnService#suspend} share one list — {@link RnTrigger#SUSPENSION_REASONS}. */
    @ParameterizedTest
    @EnumSource(RnTrigger.class)
    void suspensionReasonsAreExactlyWhatProposesSuspension(RnTrigger trigger) {
        assertThat(RnStatus.ACTIVE.canTransitionTo(RnStatus.SUSPENSION_PROPOSED, trigger))
                .isEqualTo(RnTrigger.SUSPENSION_REASONS.contains(trigger));
    }

    /** Historical reason: kept for old log rows and acts, but no new proposal may use it (MINTS). */
    @Test
    void consentExpiryNoLongerProposesSuspension() {
        assertThat(RnStatus.ACTIVE.canTransitionTo(RnStatus.SUSPENSION_PROPOSED, RnTrigger.CONSENT_EXPIRY)).isFalse();
    }

    /** A suspension reason must never jump the response phase (čl. 30. st. 2 ZUP). */
    @ParameterizedTest
    @EnumSource(value = RnTrigger.class,
            names = {"NO_DECISION", "DECISION_REVOKED", "INCOMPLETE_DOCUMENTATION", "INSPECTION", "OTHER"})
    void suspendTriggersDoNotSuspendDirectly(RnTrigger trigger) {
        assertThat(RnStatus.ACTIVE.canTransitionTo(RnStatus.SUSPENDED, trigger)).isFalse();
    }

    @Test
    void onlyTheDeadlineJobTurnsAProposalIntoASuspension() {
        assertThat(RnStatus.SUSPENSION_PROPOSED.canTransitionTo(RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED))
                .isTrue();
        assertThat(RnStatus.SUSPENSION_PROPOSED.canTransitionTo(RnStatus.SUSPENDED, RnTrigger.OTHER)).isFalse();
    }

    /**
     * Withdrawal is final (čl. 6 STR Uredbe): the only way out is an accepted objection (T12,
     * REINSTATE → ACTIVE). No other trigger leads back out.
     */
    @ParameterizedTest
    @EnumSource(RnTrigger.class)
    void withdrawnLeavesOnlyThroughReinstate(RnTrigger trigger) {
        for (RnStatus target : RnStatus.values()) {
            assertThat(RnStatus.WITHDRAWN.canTransitionTo(target, trigger))
                    .isEqualTo(trigger == RnTrigger.REINSTATE && target == RnStatus.ACTIVE);
        }
    }

    /** The lessor's revocation is final too (čl. 5. st. 5) — and it never turns into a withdrawal. */
    @ParameterizedTest
    @EnumSource(RnTrigger.class)
    void revokedIsTerminal(RnTrigger trigger) {
        for (RnStatus target : RnStatus.values()) {
            assertThat(RnStatus.REVOKED.canTransitionTo(target, trigger)).isFalse();
        }
    }

    /** The lessor may revoke from every standing status, including a pending withdrawal proposal. */
    @ParameterizedTest
    @EnumSource(value = RnStatus.class,
            names = {"ACTIVE", "SUSPENSION_PROPOSED", "SUSPENDED", "WITHDRAWAL_PROPOSED"})
    void revocationLeadsToRevokedFromEveryStandingStatus(RnStatus from) {
        assertThat(from.canTransitionTo(RnStatus.REVOKED, RnTrigger.REVOCATION)).isTrue();
    }

    /** T12: withdrawal is two-phase — every standing status goes to a proposal first, never directly. */
    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"ACTIVE", "SUSPENSION_PROPOSED", "SUSPENDED"})
    void withdrawalIsProposedFirst(RnStatus from) {
        assertThat(from.canTransitionTo(RnStatus.WITHDRAWAL_PROPOSED, RnTrigger.WITHDRAWAL_PROPOSAL)).isTrue();
        for (RnTrigger trigger : RnTrigger.values()) {
            assertThat(from.canTransitionTo(RnStatus.WITHDRAWN, trigger)).isFalse();
        }
    }

    /** A proposal can be raised only from a standing status — not again, and not from a final one. */
    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"WITHDRAWAL_PROPOSED", "REVOKED", "WITHDRAWN"})
    void withdrawalCannotBeProposedFrom(RnStatus from) {
        assertThat(from.canTransitionTo(RnStatus.WITHDRAWAL_PROPOSED, RnTrigger.WITHDRAWAL_PROPOSAL)).isFalse();
    }

    /**
     * T12: from the proposal only the deadline withdraws (no manual withdrawal, just like suspension),
     * revocation is possible, and discontinuation always returns to ACTIVE.
     */
    @Test
    void withdrawalProposal_transitions() {
        RnStatus wp = RnStatus.WITHDRAWAL_PROPOSED;
        assertThat(wp.canTransitionTo(RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED)).isTrue();
        assertThat(wp.canTransitionTo(RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL)).isFalse();
        assertThat(wp.canTransitionTo(RnStatus.REVOKED, RnTrigger.REVOCATION)).isTrue();
        assertThat(wp.canTransitionTo(RnStatus.ACTIVE, RnTrigger.REVOKE_WITHDRAWAL_PROPOSAL)).isTrue();
        assertThat(wp.canTransitionTo(RnStatus.SUSPENSION_PROPOSED, RnTrigger.REVOKE_WITHDRAWAL_PROPOSAL)).isFalse();
        assertThat(wp.canTransitionTo(RnStatus.SUSPENDED, RnTrigger.REVOKE_WITHDRAWAL_PROPOSAL)).isFalse();
        assertThat(wp.canTransitionTo(RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED)).isFalse();
        assertThat(wp.isTerminal()).isFalse();
    }

    /** WITHDRAWAL is historical: no status may be withdrawn with it any more. */
    @ParameterizedTest
    @EnumSource(RnStatus.class)
    void historicalWithdrawalTrigger_isNeverAccepted(RnStatus from) {
        for (RnStatus target : RnStatus.values()) {
            assertThat(from.canTransitionTo(target, RnTrigger.WITHDRAWAL)).isFalse();
        }
    }

    /** Each final status has its own trigger: the official cannot "revoke", the lessor cannot "withdraw". */
    @ParameterizedTest
    @EnumSource(value = RnStatus.class,
            names = {"ACTIVE", "SUSPENSION_PROPOSED", "SUSPENDED", "WITHDRAWAL_PROPOSED"})
    void finalStatusesDoNotShareTriggers(RnStatus from) {
        assertThat(from.canTransitionTo(RnStatus.WITHDRAWN, RnTrigger.REVOCATION)).isFalse();
        assertThat(from.canTransitionTo(RnStatus.REVOKED, RnTrigger.WITHDRAWAL)).isFalse();
    }

    @Test
    void onlyRevokedAndWithdrawnAreTerminal() {
        assertThat(RnStatus.REVOKED.isTerminal()).isTrue();
        assertThat(RnStatus.WITHDRAWN.isTerminal()).isTrue();
        assertThat(RnStatus.ACTIVE.isTerminal()).isFalse();
        assertThat(RnStatus.SUSPENSION_PROPOSED.isTerminal()).isFalse();
        assertThat(RnStatus.SUSPENDED.isTerminal()).isFalse();
    }

    /**
     * A proposal is an intermediate step, not a suspension: the registration number keeps
     * standing while the response deadline runs, so everything outside the suspension procedure
     * itself — public verification, the registry's active view, statistics, the activity
     * report's anomaly count — must treat it as active.
     */
    @Test
    void proposedSuspensionStillCountsAsActive() {
        assertThat(RnStatus.SUSPENSION_PROPOSED.isActiveForPublicUse()).isTrue();
        // T12: a withdrawal proposal likewise only opens the response deadline.
        assertThat(RnStatus.WITHDRAWAL_PROPOSED.isActiveForPublicUse()).isTrue();
        assertThat(RnStatus.ACTIVE.isActiveForPublicUse()).isTrue();

        assertThat(RnStatus.SUSPENDED.isActiveForPublicUse()).isFalse();
        assertThat(RnStatus.REVOKED.isActiveForPublicUse()).isFalse();
        assertThat(RnStatus.WITHDRAWN.isActiveForPublicUse()).isFalse();
    }
}
