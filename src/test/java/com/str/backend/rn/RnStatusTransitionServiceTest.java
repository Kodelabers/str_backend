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
        service = new RnStatusTransitionService(rnLogRepository);
    }

    // --- Valid transitions ---

    @Test
    void active_to_suspended_via_inspection() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENDED, RnTrigger.INSPECTION);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENDED);
        verifyLog("ACTIVE", "SUSPENDED", "INSPECTION");
    }

    @Test
    void active_to_suspended_via_consent_expiry() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENDED, RnTrigger.CONSENT_EXPIRY);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENDED);
        verifyLog("ACTIVE", "SUSPENDED", "CONSENT_EXPIRY");
    }

    @Test
    void active_to_suspended_via_incomplete_documentation() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENDED, RnTrigger.INCOMPLETE_DOCUMENTATION);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENDED);
        verifyLog("ACTIVE", "SUSPENDED", "INCOMPLETE_DOCUMENTATION");
    }

    @Test
    void active_to_withdrawn_via_withdrawal() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        verifyLog("ACTIVE", "WITHDRAWN", "WITHDRAWAL");
    }

    @Test
    void suspended_to_active_via_reactivate() {
        RnEntity rn = suspended();
        service.transition(rn, RnStatus.ACTIVE, RnTrigger.REACTIVATE);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.ACTIVE);
        verifyLog("SUSPENDED", "ACTIVE", "REACTIVATE");
    }

    @Test
    void suspended_to_withdrawn_via_withdrawal() {
        RnEntity rn = suspended();
        service.transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        verifyLog("SUSPENDED", "WITHDRAWN", "WITHDRAWAL");
    }

    // --- Illegal transitions ---

    @Test
    void withdrawn_cannot_be_reactivated() {
        // Povlačenje/opoziv je trajno — reaktivacija samo za suspendirane (TC-STR-2.1-002, TC-STR-2.2-001).
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
        service.transition(rn, RnStatus.SUSPENDED, RnTrigger.INSPECTION);

        ArgumentCaptor<RegistrationNumberLogEntity> captor =
                ArgumentCaptor.forClass(RegistrationNumberLogEntity.class);
        verify(rnLogRepository).save(captor.capture());
        RegistrationNumberLogEntity entry = captor.getValue();

        assertThat(entry.getRn()).isEqualTo(rn.getRn());
        assertThat(entry.getFromStatus()).isEqualTo("ACTIVE");
        assertThat(entry.getToStatus()).isEqualTo("SUSPENDED");
        assertThat(entry.getTriggerName()).isEqualTo("INSPECTION");
        assertThat(entry.getOccurredAt()).isNotNull();
    }

    // --- Helpers ---

    private RnEntity active() {
        return RnEntity.issue("HR120001000000000001", UUID.randomUUID(), UUID.randomUUID(), LocalDate.now());
    }

    private RnEntity suspended() {
        RnEntity rn = active();
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
