package com.str.backend.rn;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
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

    private AuditLogRepository auditLogRepository;
    private RnStatusTransitionService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        service = new RnStatusTransitionService(auditLogRepository);
    }

    // --- Valid transitions ---

    @Test
    void active_to_suspended_via_inspection() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENDED, RnTrigger.INSPECTION);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENDED);
        verifyAuditLog("ACTIVE", "SUSPENDED", "INSPECTION");
    }

    @Test
    void active_to_suspended_via_consent_expiry() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENDED, RnTrigger.CONSENT_EXPIRY);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.SUSPENDED);
        verifyAuditLog("ACTIVE", "SUSPENDED", "CONSENT_EXPIRY");
    }

    @Test
    void active_to_withdrawn_via_withdrawal() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        verifyAuditLog("ACTIVE", "WITHDRAWN", "WITHDRAWAL");
    }

    @Test
    void suspended_to_active_via_reactivate() {
        RnEntity rn = suspended();
        service.transition(rn, RnStatus.ACTIVE, RnTrigger.REACTIVATE);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.ACTIVE);
        verifyAuditLog("SUSPENDED", "ACTIVE", "REACTIVATE");
    }

    @Test
    void suspended_to_withdrawn_via_withdrawal() {
        RnEntity rn = suspended();
        service.transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        verifyAuditLog("SUSPENDED", "WITHDRAWN", "WITHDRAWAL");
    }

    // --- Illegal transitions ---

    @Test
    void withdrawn_to_active_via_reactivate() {
        RnEntity rn = withdrawn();
        service.transition(rn, RnStatus.ACTIVE, RnTrigger.REACTIVATE);
        assertThat(rn.getStatus()).isEqualTo(RnStatus.ACTIVE);
        verifyAuditLog("WITHDRAWN", "ACTIVE", "REACTIVATE");
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

    // --- Audit log content ---

    @Test
    void audit_log_contains_correct_fields() {
        RnEntity rn = active();
        service.transition(rn, RnStatus.SUSPENDED, RnTrigger.INSPECTION);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntity log = captor.getValue();

        assertThat(log.getEntityType()).isEqualTo("RN");
        assertThat(log.getFromStatus()).isEqualTo("ACTIVE");
        assertThat(log.getToStatus()).isEqualTo("SUSPENDED");
        assertThat(log.getTriggerName()).isEqualTo("INSPECTION");
        assertThat(log.getOccurredAt()).isNotNull();
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

    private void verifyAuditLog(String from, String to, String trigger) {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntity log = captor.getValue();
        assertThat(log.getFromStatus()).isEqualTo(from);
        assertThat(log.getToStatus()).isEqualTo(to);
        assertThat(log.getTriggerName()).isEqualTo(trigger);
    }
}
