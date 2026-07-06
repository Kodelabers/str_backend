package com.str.backend.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminAuditServiceTest {

    private AdminAuditLogRepository repository;
    private AdminAuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminAuditLogRepository.class);
        service = new AdminAuditService(repository);
    }

    @Test
    void record_persistsRow() {
        service.record("officer-1", "LESSOR_APPROVE", "LESSOR", "abc", "detalj");

        ArgumentCaptor<AdminAuditLogEntity> captor = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(repository).save(captor.capture());
        AdminAuditLogEntity saved = captor.getValue();
        assertThat(saved.getActor()).isEqualTo("officer-1");
        assertThat(saved.getAction()).isEqualTo("LESSOR_APPROVE");
        assertThat(saved.getEntityType()).isEqualTo("LESSOR");
        assertThat(saved.getEntityId()).isEqualTo("abc");
        assertThat(saved.getDetails()).isEqualTo("detalj");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void record_nullActor_fallsBackToSystem() {
        service.record(null, "ACTIVITY_PURGE", "ACTIVITY", null, "removed=3");

        ArgumentCaptor<AdminAuditLogEntity> captor = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo(AdminAuditService.SYSTEM);
    }
}
