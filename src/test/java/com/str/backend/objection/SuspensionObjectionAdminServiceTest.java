package com.str.backend.objection;

import com.str.backend.admin.AdminAuditService;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.IllegalStatusTransitionException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SuspensionObjectionAdminServiceTest {

    private static final String RN = "HR120001000000000123";
    private static final String ACTOR = "INTERNAL:12345678903";

    private final SuspensionObjectionRepository repository = mock(SuspensionObjectionRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final RnService rnService = mock(RnService.class);
    private final AdminAuditService auditService = mock(AdminAuditService.class);
    private final InternalAccessService internalAccess = mock(InternalAccessService.class);
    private final LessorRepository lessorRepository = mock(LessorRepository.class);
    private final SuspensionObjectionAdminService service = new SuspensionObjectionAdminService(
            repository, rnRepository, rnService, auditService, internalAccess, lessorRepository);

    private final RnEntity rn = mock(RnEntity.class);
    private SuspensionObjectionEntity objection;

    @BeforeEach
    void submittedObjectionOnProposedRn() {
        objection = SuspensionObjectionEntity.createForOib("99999999990", RN, RnStatus.SUSPENSION_PROPOSED, null,
                "NIAS:99999999990", "prigovor.pdf", "application/pdf",
                "%PDF".getBytes(StandardCharsets.US_ASCII));
        when(repository.findByObjectionIdAndRn(objection.getObjectionId(), RN)).thenReturn(Optional.of(objection));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENSION_PROPOSED);
    }

    @Test
    void accept_onProposal_revokesProposal() {
        service.accept(RN, objection.getObjectionId(), ACTOR, "  Dostavljena suglasnost  ");

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        assertThat(objection.getResolvedBy()).isEqualTo(ACTOR);
        assertThat(objection.getResolvedAt()).isNotNull();
        assertThat(objection.getResolutionNote()).isEqualTo("Dostavljena suglasnost");
        verify(rnService).revokeProposal(RN, ACTOR);
        verify(rnService, never()).reactivate(anyString(), anyString());
        verify(auditService).record(ACTOR, "OBJECTION_ACCEPT", "SUSPENSION_OBJECTION",
                objection.getObjectionId().toString(), "Dostavljena suglasnost");
    }

    @Test
    void accept_onSuspension_reactivates() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);

        service.accept(RN, objection.getObjectionId(), ACTOR, null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        assertThat(objection.getResolutionNote()).isNull();
        verify(rnService).reactivate(RN, ACTOR);
        verify(rnService, never()).revokeProposal(anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"ACTIVE", "REVOKED", "WITHDRAWN"})
    void accept_whenRnLeftProposalAndSuspension_is409(RnStatus status) {
        when(rn.getStatus()).thenReturn(status);

        assertThatThrownBy(() -> service.accept(RN, objection.getObjectionId(), ACTOR, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_STATUS"));
        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        verifyNoInteractions(rnService, auditService);
    }

    @Test
    void reject_keepsRn_andRecordsNote() {
        service.reject(RN, objection.getObjectionId(), ACTOR, "Nije dostavljena suglasnost");

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
        assertThat(objection.getResolutionNote()).isEqualTo("Nije dostavljena suglasnost");
        verifyNoInteractions(rnService);
        verify(auditService).record(ACTOR, "OBJECTION_REJECT", "SUSPENSION_OBJECTION",
                objection.getObjectionId().toString(), "Nije dostavljena suglasnost");
    }

    @Test
    void resolvedObjection_cannotBeResolvedAgain() {
        service.reject(RN, objection.getObjectionId(), ACTOR, null);

        assertThatThrownBy(() -> service.accept(RN, objection.getObjectionId(), ACTOR, null))
                .isInstanceOf(IllegalStatusTransitionException.class);
        verifyNoInteractions(rnService);
    }

    /** Id prigovora drugog RB-a pod ovim RB-om ne postoji — 404. */
    @Test
    void objectionOfOtherRn_is404() {
        UUID other = UUID.randomUUID();
        when(repository.findByObjectionIdAndRn(other, RN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(RN, other, ACTOR, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void list_unknownRn_is404() {
        when(rnRepository.existsById(RN)).thenReturn(false);

        assertThatThrownBy(() -> service.list(RN)).isInstanceOf(ResourceNotFoundException.class);
    }
}
