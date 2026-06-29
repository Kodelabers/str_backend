package com.str.backend.lessor;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnStatusTransitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LessorRnActionServiceTest {

    private static final String RN = "HR120001000000000001";

    private RnRepository rnRepository;
    private SubmissionRepository submissionRepository;
    private RnStatusTransitionService transitionService;

    private LessorRnActionService service;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        transitionService = mock(RnStatusTransitionService.class);
        service = new LessorRnActionService(rnRepository, submissionRepository, transitionService);
    }

    @Test
    void withdrawOwn_transitionsToWithdrawn_whenOwner() {
        UUID lessorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        RnEntity rn = rn(submissionId, RnStatus.ACTIVE);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission(submissionId, lessorId)));

        LessorRnActionResponse response = service.withdrawOwn(RN, lessorId, "  vise ne iznajmljujem  ");

        assertThat(response.rn()).isEqualTo(RN);
        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL,
                "LESSOR:" + lessorId, "vise ne iznajmljujem");
    }

    @Test
    void withdrawOwn_throwsNotFound_whenNotOwner() {
        UUID lessorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn(submissionId, RnStatus.ACTIVE)));
        when(submissionRepository.findById(submissionId))
                .thenReturn(Optional.of(submission(submissionId, UUID.randomUUID()))); // different lessor

        assertThatThrownBy(() -> service.withdrawOwn(RN, lessorId, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    }

    @Test
    void withdrawOwn_throwsNotFound_whenRnMissing() {
        when(rnRepository.findById(RN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdrawOwn(RN, UUID.randomUUID(), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private RnEntity rn(UUID submissionId, RnStatus status) {
        RnEntity rn = RnEntity.issue(RN, submissionId, UUID.randomUUID(), LocalDate.of(2026, 1, 1));
        // applyStatus is package-private to com.str.backend.rn — set the field via reflection here.
        try {
            var field = RnEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(rn, status);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return rn;
    }

    private SubmissionEntity submission(UUID submissionId, UUID lessorId) {
        SubmissionEntity submission = SubmissionEntity.create("FN-1", lessorId, 1L, Instant.now(), null, null);
        try {
            var field = SubmissionEntity.class.getDeclaredField("submissionId");
            field.setAccessible(true);
            field.set(submission, submissionId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return submission;
    }
}
