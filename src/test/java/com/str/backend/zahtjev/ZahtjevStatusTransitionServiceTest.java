package com.str.backend.zahtjev;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.domain.ZahtjevStatus;
import com.str.backend.domain.ZahtjevTrigger;
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

class ZahtjevStatusTransitionServiceTest {

    private AuditLogRepository auditLogRepository;
    private ZahtjevStatusTransitionService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        service = new ZahtjevStatusTransitionService(auditLogRepository);
    }

    // --- Valid transitions ---

    @Test
    void iniciiran_to_u_obradi_via_submit() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.INICIIRAN);
        service.transition(z, ZahtjevStatus.U_OBRADI, ZahtjevTrigger.SUBMIT);
        assertThat(z.getStatus()).isEqualTo(ZahtjevStatus.U_OBRADI);
        verifyAuditLog("INICIIRAN", "U_OBRADI", "SUBMIT");
    }

    @Test
    void iniciiran_to_u_verifikaciji_via_stranac_upload() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.INICIIRAN);
        service.transition(z, ZahtjevStatus.U_VERIFIKACIJI, ZahtjevTrigger.STRANAC_UPLOAD);
        assertThat(z.getStatus()).isEqualTo(ZahtjevStatus.U_VERIFIKACIJI);
        verifyAuditLog("INICIIRAN", "U_VERIFIKACIJI", "STRANAC_UPLOAD");
    }

    @Test
    void u_verifikaciji_to_u_obradi_via_referent_approve() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.U_VERIFIKACIJI);
        service.transition(z, ZahtjevStatus.U_OBRADI, ZahtjevTrigger.REFERENT_APPROVE);
        assertThat(z.getStatus()).isEqualTo(ZahtjevStatus.U_OBRADI);
        verifyAuditLog("U_VERIFIKACIJI", "U_OBRADI", "REFERENT_APPROVE");
    }

    @Test
    void u_obradi_to_prihvacen_via_validation_passed() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.U_OBRADI);
        service.transition(z, ZahtjevStatus.PRIHVACEN, ZahtjevTrigger.VALIDATION_PASSED);
        assertThat(z.getStatus()).isEqualTo(ZahtjevStatus.PRIHVACEN);
        verifyAuditLog("U_OBRADI", "PRIHVACEN", "VALIDATION_PASSED");
    }

    @Test
    void u_obradi_to_odbijen_via_validation_rejected() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.U_OBRADI);
        service.transition(z, ZahtjevStatus.ODBIJEN, ZahtjevTrigger.VALIDATION_REJECTED);
        assertThat(z.getStatus()).isEqualTo(ZahtjevStatus.ODBIJEN);
        verifyAuditLog("U_OBRADI", "ODBIJEN", "VALIDATION_REJECTED");
    }

    // --- Illegal transitions ---

    @Test
    void prihvacen_is_terminal() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.PRIHVACEN);
        assertThatThrownBy(() -> service.transition(z, ZahtjevStatus.U_OBRADI, ZahtjevTrigger.SUBMIT))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(z.getStatus()).isEqualTo(ZahtjevStatus.PRIHVACEN);
    }

    @Test
    void odbijen_is_terminal() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.ODBIJEN);
        assertThatThrownBy(() -> service.transition(z, ZahtjevStatus.U_OBRADI, ZahtjevTrigger.SUBMIT))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(z.getStatus()).isEqualTo(ZahtjevStatus.ODBIJEN);
    }

    @Test
    void iniciiran_cannot_skip_to_prihvacen() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.INICIIRAN);
        assertThatThrownBy(() -> service.transition(z, ZahtjevStatus.PRIHVACEN, ZahtjevTrigger.VALIDATION_PASSED))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void u_verifikaciji_cannot_go_back_to_iniciiran() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.U_VERIFIKACIJI);
        assertThatThrownBy(() -> service.transition(z, ZahtjevStatus.INICIIRAN, ZahtjevTrigger.SUBMIT))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    // --- Audit log content ---

    @Test
    void audit_log_contains_correct_fields() {
        ZahtjevEntity z = zahtjev(ZahtjevStatus.U_OBRADI);
        service.transition(z, ZahtjevStatus.PRIHVACEN, ZahtjevTrigger.VALIDATION_PASSED);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntity log = captor.getValue();

        assertThat(log.getEntityType()).isEqualTo("ZAHTJEV");
        assertThat(log.getFromStatus()).isEqualTo("U_OBRADI");
        assertThat(log.getToStatus()).isEqualTo("PRIHVACEN");
        assertThat(log.getTriggerName()).isEqualTo("VALIDATION_PASSED");
        assertThat(log.getOccurredAt()).isNotNull();
    }

    // --- Helpers ---

    private ZahtjevEntity zahtjev(ZahtjevStatus status) {
        ZahtjevEntity z = new ZahtjevEntity();
        setField(z, "idZahtjeva", UUID.randomUUID());
        setField(z, "status", status);
        return z;
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
