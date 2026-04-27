package com.str.backend.rb;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.domain.RbStatus;
import com.str.backend.domain.RbTrigger;
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

class RbStatusTransitionServiceTest {

    private AuditLogRepository auditLogRepository;
    private RbStatusTransitionService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        service = new RbStatusTransitionService(auditLogRepository);
    }

    // --- Valid transitions ---

    @Test
    void aktivan_to_suspendiran_via_inspection() {
        RbEntity rb = aktivan();
        service.transition(rb, RbStatus.SUSPENDIRAN, RbTrigger.INSPECTION);
        assertThat(rb.getStatus()).isEqualTo(RbStatus.SUSPENDIRAN);
        verifyAuditLog("AKTIVAN", "SUSPENDIRAN", "INSPECTION");
    }

    @Test
    void aktivan_to_suspendiran_via_consent_expiry() {
        RbEntity rb = aktivan();
        service.transition(rb, RbStatus.SUSPENDIRAN, RbTrigger.CONSENT_EXPIRY);
        assertThat(rb.getStatus()).isEqualTo(RbStatus.SUSPENDIRAN);
        verifyAuditLog("AKTIVAN", "SUSPENDIRAN", "CONSENT_EXPIRY");
    }

    @Test
    void aktivan_to_povucen_via_withdrawal() {
        RbEntity rb = aktivan();
        service.transition(rb, RbStatus.POVUCEN, RbTrigger.WITHDRAWAL);
        assertThat(rb.getStatus()).isEqualTo(RbStatus.POVUCEN);
        verifyAuditLog("AKTIVAN", "POVUCEN", "WITHDRAWAL");
    }

    @Test
    void suspendiran_to_aktivan_via_reactivate() {
        RbEntity rb = suspendiran();
        service.transition(rb, RbStatus.AKTIVAN, RbTrigger.REACTIVATE);
        assertThat(rb.getStatus()).isEqualTo(RbStatus.AKTIVAN);
        verifyAuditLog("SUSPENDIRAN", "AKTIVAN", "REACTIVATE");
    }

    @Test
    void suspendiran_to_povucen_via_withdrawal() {
        RbEntity rb = suspendiran();
        service.transition(rb, RbStatus.POVUCEN, RbTrigger.WITHDRAWAL);
        assertThat(rb.getStatus()).isEqualTo(RbStatus.POVUCEN);
        verifyAuditLog("SUSPENDIRAN", "POVUCEN", "WITHDRAWAL");
    }

    // --- Illegal transitions ---

    @Test
    void povucen_to_aktivan_via_reactivate() {
        RbEntity rb = povucen();
        service.transition(rb, RbStatus.AKTIVAN, RbTrigger.REACTIVATE);
        assertThat(rb.getStatus()).isEqualTo(RbStatus.AKTIVAN);
        verifyAuditLog("POVUCEN", "AKTIVAN", "REACTIVATE");
    }

    @Test
    void povucen_cannot_be_withdrawn_again() {
        RbEntity rb = povucen();
        assertThatThrownBy(() -> service.transition(rb, RbStatus.POVUCEN, RbTrigger.WITHDRAWAL))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(rb.getStatus()).isEqualTo(RbStatus.POVUCEN);
    }

    @Test
    void aktivan_cannot_be_reactivated() {
        RbEntity rb = aktivan();
        assertThatThrownBy(() -> service.transition(rb, RbStatus.AKTIVAN, RbTrigger.REACTIVATE))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void suspendiran_cannot_be_suspended_again() {
        RbEntity rb = suspendiran();
        assertThatThrownBy(() -> service.transition(rb, RbStatus.SUSPENDIRAN, RbTrigger.INSPECTION))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void povucen_cannot_be_suspended() {
        RbEntity rb = povucen();
        assertThatThrownBy(() -> service.transition(rb, RbStatus.SUSPENDIRAN, RbTrigger.INSPECTION))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    // --- Audit log content ---

    @Test
    void audit_log_contains_correct_fields() {
        RbEntity rb = aktivan();
        service.transition(rb, RbStatus.SUSPENDIRAN, RbTrigger.INSPECTION);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntity log = captor.getValue();

        assertThat(log.getEntityType()).isEqualTo("RB");
        assertThat(log.getFromStatus()).isEqualTo("AKTIVAN");
        assertThat(log.getToStatus()).isEqualTo("SUSPENDIRAN");
        assertThat(log.getTriggerName()).isEqualTo("INSPECTION");
        assertThat(log.getOccurredAt()).isNotNull();
    }

    // --- Helpers ---

    private RbEntity aktivan() {
        return RbEntity.issue("HR12345678", UUID.randomUUID(), UUID.randomUUID(), LocalDate.now());
    }

    private RbEntity suspendiran() {
        RbEntity rb = aktivan();
        rb.applyStatus(RbStatus.SUSPENDIRAN);
        return rb;
    }

    private RbEntity povucen() {
        RbEntity rb = aktivan();
        rb.applyStatus(RbStatus.POVUCEN);
        return rb;
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
