package com.str.backend.request;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.domain.SubmissionStatus;
import com.str.backend.domain.SubmissionTrigger;
import com.str.backend.exception.IllegalStatusTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SubmissionStatusTransitionServiceTest {

    private AuditLogRepository auditLogRepository;
    private SubmissionStatusTransitionService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        service = new SubmissionStatusTransitionService(auditLogRepository);
    }

    // --- Valid transitions ---

    @Test
    void initiated_to_in_processing_via_submit() {
        SubmissionEntity s = submission(SubmissionStatus.INITIATED);
        service.transition(s, SubmissionStatus.IN_PROCESSING, SubmissionTrigger.SUBMIT);
        assertThat(s.getStatus()).isEqualTo(SubmissionStatus.IN_PROCESSING);
        verifyAuditLog("INITIATED", "IN_PROCESSING", "SUBMIT");
    }

    @Test
    void initiated_to_in_verification_via_foreign_upload() {
        SubmissionEntity s = submission(SubmissionStatus.INITIATED);
        service.transition(s, SubmissionStatus.IN_VERIFICATION, SubmissionTrigger.FOREIGN_UPLOAD);
        assertThat(s.getStatus()).isEqualTo(SubmissionStatus.IN_VERIFICATION);
        verifyAuditLog("INITIATED", "IN_VERIFICATION", "FOREIGN_UPLOAD");
    }

    @Test
    void in_verification_to_in_processing_via_referent_approve() {
        SubmissionEntity s = submission(SubmissionStatus.IN_VERIFICATION);
        service.transition(s, SubmissionStatus.IN_PROCESSING, SubmissionTrigger.REFERENT_APPROVE);
        assertThat(s.getStatus()).isEqualTo(SubmissionStatus.IN_PROCESSING);
        verifyAuditLog("IN_VERIFICATION", "IN_PROCESSING", "REFERENT_APPROVE");
    }

    @Test
    void in_processing_to_accepted_via_validation_passed() {
        SubmissionEntity s = submission(SubmissionStatus.IN_PROCESSING);
        service.transition(s, SubmissionStatus.ACCEPTED, SubmissionTrigger.VALIDATION_PASSED);
        assertThat(s.getStatus()).isEqualTo(SubmissionStatus.ACCEPTED);
        verifyAuditLog("IN_PROCESSING", "ACCEPTED", "VALIDATION_PASSED");
    }

    @Test
    void in_processing_to_rejected_via_validation_rejected() {
        SubmissionEntity s = submission(SubmissionStatus.IN_PROCESSING);
        service.transition(s, SubmissionStatus.REJECTED, SubmissionTrigger.VALIDATION_REJECTED);
        assertThat(s.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
        verifyAuditLog("IN_PROCESSING", "REJECTED", "VALIDATION_REJECTED");
    }

    // --- Illegal transitions ---

    @Test
    void accepted_is_terminal() {
        SubmissionEntity s = submission(SubmissionStatus.ACCEPTED);
        assertThatThrownBy(() -> service.transition(s, SubmissionStatus.IN_PROCESSING, SubmissionTrigger.SUBMIT))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(s.getStatus()).isEqualTo(SubmissionStatus.ACCEPTED);
    }

    @Test
    void rejected_is_terminal() {
        SubmissionEntity s = submission(SubmissionStatus.REJECTED);
        assertThatThrownBy(() -> service.transition(s, SubmissionStatus.IN_PROCESSING, SubmissionTrigger.SUBMIT))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(s.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
    }

    @Test
    void initiated_cannot_skip_to_accepted() {
        SubmissionEntity s = submission(SubmissionStatus.INITIATED);
        assertThatThrownBy(() -> service.transition(s, SubmissionStatus.ACCEPTED, SubmissionTrigger.VALIDATION_PASSED))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void in_verification_cannot_go_back_to_initiated() {
        SubmissionEntity s = submission(SubmissionStatus.IN_VERIFICATION);
        assertThatThrownBy(() -> service.transition(s, SubmissionStatus.INITIATED, SubmissionTrigger.SUBMIT))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    // --- Audit log content ---

    @Test
    void audit_log_contains_correct_fields() {
        SubmissionEntity s = submission(SubmissionStatus.IN_PROCESSING);
        service.transition(s, SubmissionStatus.ACCEPTED, SubmissionTrigger.VALIDATION_PASSED);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntity log = captor.getValue();

        assertThat(log.getEntityType()).isEqualTo("ZAHTJEV");
        assertThat(log.getFromStatus()).isEqualTo("IN_PROCESSING");
        assertThat(log.getToStatus()).isEqualTo("ACCEPTED");
        assertThat(log.getTriggerName()).isEqualTo("VALIDATION_PASSED");
        assertThat(log.getOccurredAt()).isNotNull();
    }

    // --- Helpers ---

    private SubmissionEntity submission(SubmissionStatus status) {
        SubmissionEntity s = new SubmissionEntity();
        setField(s, "submissionId", UUID.randomUUID());
        setField(s, "status", status);
        return s;
    }

    private void verifyAuditLog(String from, String to, String trigger) {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntity log = captor.getValue();
        assertThat(log.getFromStatus()).isEqualTo(from);
        assertThat(log.getToStatus()).isEqualTo(to);
        assertThat(log.getTriggerName()).isEqualTo(trigger);
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
