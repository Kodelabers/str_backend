package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.IllegalStatusTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RnStatusTransitionServiceTest {

    private RegistrationNumberLogRepository rnLogRepository;
    private RnStatusTransitionService service;

    @BeforeEach
    void setUp() {
        rnLogRepository = mock(RegistrationNumberLogRepository.class);
        service = new RnStatusTransitionService(rnLogRepository,
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    // --- Valid transitions ---

    @Test
    void active_to_suspension_proposed_via_inspection() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INSPECTION);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENSION_PROPOSED);
        verifyLog("ACTIVE", "SUSPENSION_PROPOSED", "INSPECTION");
    }

    @Test
    void active_to_suspension_proposed_via_no_decision() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.NO_DECISION);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENSION_PROPOSED);
        verifyLog("ACTIVE", "SUSPENSION_PROPOSED", "NO_DECISION");
    }

    @Test
    void active_to_suspension_proposed_via_incomplete_documentation() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INCOMPLETE_DOCUMENTATION);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENSION_PROPOSED);
        verifyLog("ACTIVE", "SUSPENSION_PROPOSED", "INCOMPLETE_DOCUMENTATION");
    }

    @Test
    void suspension_proposed_to_active_via_revoke_proposal() {
        RnEntity rn = suspensionProposed();
        service.transition(rn, RnStatus.ACTIVE, RnTrigger.REVOKE_PROPOSAL);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.ACTIVE);
        verifyLog("SUSPENSION_PROPOSED", "ACTIVE", "REVOKE_PROPOSAL");
    }

    @Test
    void suspension_proposed_to_suspended_via_deadline_exceeded() {
        RnEntity rn = suspensionProposed();
        service.transition(rn, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENDED);
        verifyLog("SUSPENSION_PROPOSED", "SUSPENDED", "DEADLINE_EXCEEDED");
    }

    @Test
    void suspension_proposed_to_withdrawal_proposed_endsSuspensionProcedure() {
        RnEntity rn = suspensionProposed();
        rn.setSuspensionDeadline(LocalDate.now().plusDays(5));
        service.transition(rn, RnStatus.WITHDRAWAL_PROPOSED, RnTrigger.WITHDRAWAL_PROPOSAL);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWAL_PROPOSED);
        assertThat(rn.getSuspensionDeadline()).as("postupak suspenzije završava").isNull();
        verifyLog("SUSPENSION_PROPOSED", "WITHDRAWAL_PROPOSED", "WITHDRAWAL_PROPOSAL");
    }

    @Test
    void withdrawal_proposed_to_withdrawn_onlyByDeadline() {
        RnEntity rn = active();
        rn.applyStatus(RnStatus.WITHDRAWAL_PROPOSED);
        rn.setWithdrawalDeadline(LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> service.transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL))
                .as("ručnog povlačenja nema").isInstanceOf(IllegalStatusTransitionException.class);

        service.transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        assertThat(rn.getWithdrawalDeadline()).isNull();
        assertThat(rn.getValidTo()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Europe/Zagreb")));
        verifyLog("WITHDRAWAL_PROPOSED", "WITHDRAWN", "WITHDRAWAL_DEADLINE_EXCEEDED");
    }

    @Test
    void suspended_to_active_via_reactivate() {
        RnEntity rn = suspended();
        service.transition(rn, RnStatus.ACTIVE, RnTrigger.REACTIVATE);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.ACTIVE);
        verifyLog("SUSPENDED", "ACTIVE", "REACTIVATE");
    }

    @Test
    void suspended_cannot_be_withdrawn_directly() {
        RnEntity rn = suspended();
        assertThatThrownBy(() -> service.transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENDED);
    }

    /** T12: uvaženi prigovor vraća povučen broj u ACTIVE i briše dan povlačenja. */
    @Test
    void withdrawn_to_active_via_reinstate_clearsValidTo() {
        RnEntity rn = active();
        rn.applyStatus(RnStatus.WITHDRAWN);
        assertThat(rn.getValidTo()).isNotNull();
        service.transition(rn, RnStatus.ACTIVE, RnTrigger.REINSTATE);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.ACTIVE);
        assertThat(rn.getValidTo()).isNull();
        verifyLog("WITHDRAWN", "ACTIVE", "REINSTATE");
    }

    // --- Illegal transitions ---

    @Test
    void active_cannot_transition_directly_to_suspended() {
        RnEntity rn = active();
        assertThatThrownBy(() -> service.transition(rn, RnStatus.SUSPENDED, RnTrigger.INSPECTION))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.ACTIVE);
    }

    @Test
    void withdrawn_cannot_be_reactivated() {
        RnEntity rn = withdrawn();
        assertThatThrownBy(() -> service.transition(rn, RnStatus.ACTIVE, RnTrigger.REACTIVATE))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWN);
    }

    @Test
    void withdrawn_cannot_be_withdrawn_again() {
        RnEntity rn = withdrawn();
        assertThatThrownBy(() -> service.transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWN);
    }

    @Test
    void active_cannot_be_reactivated() {
        RnEntity rn = active();
        assertThatThrownBy(() -> service.transition(rn, RnStatus.ACTIVE, RnTrigger.REACTIVATE))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void suspended_cannot_be_suspended_again() {
        RnEntity rn = suspended();
        assertThatThrownBy(() -> service.transition(rn, RnStatus.SUSPENDED, RnTrigger.INSPECTION))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void withdrawn_cannot_be_suspended() {
        RnEntity rn = withdrawn();
        assertThatThrownBy(() -> service.transition(rn, RnStatus.SUSPENDED, RnTrigger.INSPECTION))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    // --- Log content ---

    @Test
    void log_contains_correct_fields() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INSPECTION);

        ArgumentCaptor<RegistrationNumberLogEntity> captor =
                ArgumentCaptor.forClass(RegistrationNumberLogEntity.class);
        verify(rnLogRepository).save(captor.capture());
        RegistrationNumberLogEntity entry = captor.getValue();

        assertThat(entry.getRn()).isEqualTo(rn.getRn());
        assertThat(entry.getFromStatus()).isEqualTo("ACTIVE");
        assertThat(entry.getToStatus()).isEqualTo("SUSPENSION_PROPOSED");
        assertThat(entry.getTriggerName()).isEqualTo("INSPECTION");
        assertThat(entry.getOccurredAt()).isNotNull();
    }

    // --- Helpers ---

    private RnEntity active() {
        return RnEntity.issue("HR120001000000000001", UUID.randomUUID(), UUID.randomUUID(), LocalDate.now());
    }

    private RnEntity suspensionProposed() {
        RnEntity rn = active();
        rn.applyStatus(RnStatus.SUSPENSION_PROPOSED);
        return rn;
    }

    private RnEntity suspended() {
        RnEntity rn = suspensionProposed();
        rn.applyStatus(RnStatus.SUSPENDED);
        return rn;
    }

    private RnEntity withdrawn() {
        RnEntity rn = active();
        rn.applyStatus(RnStatus.WITHDRAWN);
        return rn;
    }

    private void verifyLog(String from, String to, String trigger) {
        ArgumentCaptor<RegistrationNumberLogEntity> captor =
                ArgumentCaptor.forClass(RegistrationNumberLogEntity.class);
        verify(rnLogRepository).save(captor.capture());
        RegistrationNumberLogEntity entry = captor.getValue();
        assertThat(entry.getFromStatus()).isEqualTo(from);
        assertThat(entry.getToStatus()).isEqualTo(to);
        assertThat(entry.getTriggerName()).isEqualTo(trigger);
    }
}
