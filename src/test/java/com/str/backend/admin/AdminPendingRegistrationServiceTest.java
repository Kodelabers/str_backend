package com.str.backend.admin;

import com.str.backend.address.CountryRepository;
import com.str.backend.domain.LessorApplicationStatus;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorDocumentRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPendingRegistrationServiceTest {

    private LessorRepository lessorRepository;
    private ApplicationEventPublisher eventPublisher;
    private AdminAuditService auditService;
    private AdminPendingRegistrationService service;

    @BeforeEach
    void setUp() {
        lessorRepository = mock(LessorRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        auditService = mock(AdminAuditService.class);
        service = new AdminPendingRegistrationService(
                lessorRepository, mock(LessorDocumentRepository.class),
                mock(CountryRepository.class), eventPublisher, auditService);
    }

    @Test
    void approve_writesAuditAndPublishesEvent() {
        UUID lessorId = UUID.randomUUID();
        LessorEntity lessor = mock(LessorEntity.class);
        when(lessorRepository.findByLessorIdAndApplicationStatus(lessorId, LessorApplicationStatus.PENDING))
                .thenReturn(Optional.of(lessor));

        service.approve(lessorId, "officer-7");

        verify(lessor).approveRegistration("officer-7");
        verify(auditService).record("officer-7", "LESSOR_APPROVE", "LESSOR", lessorId.toString(), null);
    }

    @Test
    void reject_writesAuditAndPublishesEvent() {
        UUID lessorId = UUID.randomUUID();
        LessorEntity lessor = mock(LessorEntity.class);
        when(lessorRepository.findByLessorIdAndApplicationStatus(lessorId, LessorApplicationStatus.PENDING))
                .thenReturn(Optional.of(lessor));

        service.reject(lessorId, "officer-7");

        verify(lessor).rejectRegistration("officer-7");
        verify(auditService).record("officer-7", "LESSOR_REJECT", "LESSOR", lessorId.toString(), null);
    }

    @Test
    void approve_throwsNotFound_andSkipsAudit_whenNotPending() {
        UUID lessorId = UUID.randomUUID();
        when(lessorRepository.findByLessorIdAndApplicationStatus(lessorId, LessorApplicationStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(lessorId, "officer-7"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }
}
