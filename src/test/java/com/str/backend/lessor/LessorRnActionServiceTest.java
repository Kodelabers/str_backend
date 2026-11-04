package com.str.backend.lessor;

import java.util.List;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.attachment.PrilogService;
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
    private LessorRepository lessorRepository;
    private RnStatusTransitionService transitionService;
    private PrilogService prilogService;

    private LessorRnActionService service;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        lessorRepository = mock(LessorRepository.class);
        transitionService = mock(RnStatusTransitionService.class);
        prilogService = mock(PrilogService.class);
        service = new LessorRnActionService(rnRepository, submissionRepository, lessorRepository, transitionService,
                prilogService);
    }

    /** Opoziv iznajmljivača je zaseban konačni status — Opozvan, ne Povučen (mjera službenika). */
    @Test
    void withdrawOwn_transitionsToRevoked_whenOwner() {
        UUID lessorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        RnEntity rn = rn(submissionId, RnStatus.ACTIVE);
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission(submissionId, lessorId)));

        LessorRnActionResponse response = service.withdrawOwn(RN, lessorId, "  vise ne iznajmljujem  ", List.of());

        assertThat(response.rn()).isEqualTo(RN);
        verify(transitionService).transition(rn, RnStatus.REVOKED, RnTrigger.REVOCATION,
                "LESSOR:" + lessorId, "vise ne iznajmljujem");
    }

    @Test
    void withdrawOwnByOib_transitionsToRevoked_whenOwner() {
        UUID lessorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        RnEntity rn = rn(submissionId, RnStatus.SUSPENDED);
        LessorEntity lessor = mock(LessorEntity.class);
        when(lessor.getLessorOib()).thenReturn("12312312316");
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission(submissionId, lessorId)));
        when(lessorRepository.findById(lessorId)).thenReturn(Optional.of(lessor));

        service.withdrawOwnByOib(RN, "12312312316", null, List.of());

        verify(transitionService).transition(rn, RnStatus.REVOKED, RnTrigger.REVOCATION,
                "NIAS:12312312316", null);
    }

    /** Prilozi opoziva (T17) vežu se uz zapis prijelaza REVOCATION, pod učitavateljem stranke. */
    @Test
    void withdrawOwn_withAttachments_bindsToRevocationLog() {
        UUID lessorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        RnEntity rn = rn(submissionId, RnStatus.ACTIVE);
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission(submissionId, lessorId)));
        UUID logId = UUID.randomUUID();
        when(transitionService.transition(rn, RnStatus.REVOKED, RnTrigger.REVOCATION, "LESSOR:" + lessorId, null))
                .thenReturn(logId);

        service.withdrawOwn(RN, lessorId, null, List.of(3L, 4L));

        verify(prilogService).bind("LESSOR:" + lessorId, List.of(3L, 4L), PrilogVlasnik.prijelaz(logId));
    }

    @Test
    void withdrawOwnByOib_withAttachments_bindsUnderOwnerOib() {
        UUID lessorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        RnEntity rn = rn(submissionId, RnStatus.ACTIVE);
        LessorEntity lessor = mock(LessorEntity.class);
        when(lessor.getLessorOib()).thenReturn("12312312316");
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission(submissionId, lessorId)));
        when(lessorRepository.findById(lessorId)).thenReturn(Optional.of(lessor));
        UUID logId = UUID.randomUUID();
        when(transitionService.transition(rn, RnStatus.REVOKED, RnTrigger.REVOCATION, "NIAS:12312312316", null))
                .thenReturn(logId);

        service.withdrawOwnByOib(RN, "12312312316", null, List.of(5L));

        verify(prilogService).bind("NIAS:12312312316", List.of(5L), PrilogVlasnik.prijelaz(logId));
    }

    /** Tuđi RB: 404 prije vezanja — prilog ostaje nevezan. */
    @Test
    void withdrawOwn_notOwner_doesNotBind() {
        UUID submissionId = UUID.randomUUID();
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn(submissionId, RnStatus.ACTIVE)));
        when(submissionRepository.findById(submissionId))
                .thenReturn(Optional.of(submission(submissionId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.withdrawOwn(RN, UUID.randomUUID(), null, List.of(1L)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(prilogService, never()).bind(any(), any(), any());
    }

    @Test
    void withdrawOwn_throwsNotFound_whenNotOwner() {
        UUID lessorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn(submissionId, RnStatus.ACTIVE)));
        when(submissionRepository.findById(submissionId))
                .thenReturn(Optional.of(submission(submissionId, UUID.randomUUID()))); // different lessor

        assertThatThrownBy(() -> service.withdrawOwn(RN, lessorId, null, List.of()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    }

    @Test
    void withdrawOwn_throwsNotFound_whenRnMissing() {
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdrawOwn(RN, UUID.randomUUID(), null, List.of()))
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
