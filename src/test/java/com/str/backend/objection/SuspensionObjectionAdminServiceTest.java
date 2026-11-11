package com.str.backend.objection;

import java.util.List;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.attachment.TestPrilog;
import com.str.backend.attachment.PrilogDto;
import com.str.backend.attachment.PrilogService;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    private final PrilogService prilogService = mock(PrilogService.class);
    private final com.str.backend.rn.RnReissueGuard reissueGuard = mock(com.str.backend.rn.RnReissueGuard.class);
    private final SuspensionObjectionAdminService service = new SuspensionObjectionAdminService(
            repository, rnRepository, rnService, auditService, internalAccess, lessorRepository, prilogService,
            reissueGuard);

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
        verify(rnService).revokeProposal(RN, ACTOR, null);
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
        verify(rnService).reactivate(RN, ACTOR, null);
        verify(rnService, never()).revokeProposal(anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"ACTIVE", "REVOKED"})
    void accept_whenRnLeftProposalAndSuspension_is409(RnStatus status) {
        when(rn.getStatus()).thenReturn(status);

        assertThatThrownBy(() -> service.accept(RN, objection.getObjectionId(), ACTOR, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_STATUS"));
        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        verifyNoInteractions(rnService, auditService);
    }

    /**
     * Neuvažavanje na prijedlogu: o suspenziji odlučuje {@link RnService#suspendIfDeadlineExpired}
     * (suspendira samo ako je rok istekao), uvijek tek nakon što je prigovor REJECTED.
     */
    @Test
    void reject_onProposal_recordsNote_andSuspendsIfDeadlineExpired() {
        when(rnService.suspendIfDeadlineExpired(RN, ACTOR)).thenAnswer(inv -> {
            assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
            return true;
        });

        service.reject(RN, objection.getObjectionId(), ACTOR, "Nije dostavljena suglasnost");

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
        assertThat(objection.getResolutionNote()).isEqualTo("Nije dostavljena suglasnost");
        verify(rnService, times(1)).suspendIfDeadlineExpired(RN, ACTOR);
        verifyNoMoreInteractions(rnService);
        verify(auditService).record(ACTOR, "OBJECTION_REJECT", "SUSPENSION_OBJECTION",
                objection.getObjectionId().toString(), "Nije dostavljena suglasnost");
    }

    // ---- T12: prigovor (očitovanje) na prijedlog povlačenja i na povučen broj

    /** Uvaženo očitovanje na prijedlog povlačenja obustavlja postupak (povratak u status prije prijedloga). */
    @Test
    void accept_onWithdrawalProposal_revokesWithdrawalProposal() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWAL_PROPOSED);

        service.accept(RN, objection.getObjectionId(), ACTOR, null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        verify(rnService).revokeWithdrawalProposal(RN, ACTOR, null);
        verifyNoMoreInteractions(rnService);
    }

    /** Uvaženi prigovor na povučen broj vraća ga u ACTIVE. */
    @Test
    void accept_onWithdrawn_reinstates() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);

        service.accept(RN, objection.getObjectionId(), ACTOR, null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        verify(rnService).reinstate(RN, ACTOR, null);
        verifyNoMoreInteractions(rnService);
    }

    /**
     * Za objekt je u međuvremenu izdan novi broj: 409 OBJECTION_RN_REISSUED izlazi iz transakcijske
     * metode (rollback i prigovora) i ništa se ne audita.
     */
    @Test
    void accept_onWithdrawn_reissued_is409() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);
        when(rnService.reinstate(RN, ACTOR, null))
                .thenThrow(new ConflictException("error.objection.rnReissued", "OBJECTION_RN_REISSUED"));

        assertThatThrownBy(() -> service.accept(RN, objection.getObjectionId(), ACTOR, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_REISSUED"));
        verifyNoInteractions(auditService);
    }

    /** Neuvaženo očitovanje na prijedlog povlačenja: povlači odmah ako je rok istekao. */
    @Test
    void reject_onWithdrawalProposal_withdrawsIfDeadlineExpired() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWAL_PROPOSED);
        when(rnService.withdrawIfDeadlineExpired(RN, ACTOR)).thenAnswer(inv -> {
            assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
            return true;
        });

        service.reject(RN, objection.getObjectionId(), ACTOR, null);

        verify(rnService).withdrawIfDeadlineExpired(RN, ACTOR);
        verifyNoMoreInteractions(rnService);
    }

    /** Neuvaženi prigovor na povučen broj ne mijenja broj. */
    @Test
    void reject_onWithdrawn_keepsRn() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);

        service.reject(RN, objection.getObjectionId(), ACTOR, null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
        verifyNoInteractions(rnService);
    }

    /** Na suspendiranom RB-u neuvažavanje ne radi ništa s RB-om. */
    @Test
    void reject_onSuspension_keepsRn() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);

        service.reject(RN, objection.getObjectionId(), ACTOR, null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
        verifyNoInteractions(rnService);
    }

    @Test
    void resolvedObjection_cannotBeResolvedAgain() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);
        service.reject(RN, objection.getObjectionId(), ACTOR, null);

        assertThatThrownBy(() -> service.accept(RN, objection.getObjectionId(), ACTOR, null))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThatThrownBy(() -> service.reject(RN, objection.getObjectionId(), ACTOR, null))
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

    /** Povijest nosi sve dodatne datoteke prigovora, s poveznicom za preuzimanje uz RB. */
    @Test
    void list_carriesObjectionAttachments() {
        UUID id = objection.getObjectionId();
        SuspensionObjectionRepository.ObjectionSummary row = mock(SuspensionObjectionRepository.ObjectionSummary.class);
        when(row.getObjectionId()).thenReturn(id);
        when(row.getRn()).thenReturn(RN);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(repository.findSummariesByRn(RN)).thenReturn(List.of(row));
        when(internalAccess.displayNames(any())).thenReturn(new java.util.HashMap<>());
        when(prilogService.zaVlasnike(VrstaVlasnika.OBJECTION, List.of(id))).thenReturn(java.util.Map.of(id, List.of(
                TestPrilog.vezan(21, VrstaVlasnika.OBJECTION, id, "foto.png", "NIAS:99999999990"),
                TestPrilog.vezan(22, VrstaVlasnika.OBJECTION, id, "dopis.pdf", "NIAS:99999999990"))));

        List<SuspensionObjectionDto> dtos = service.list(RN);

        assertThat(dtos).singleElement().satisfies(d -> {
            assertThat(d.prilozi()).extracting(PrilogDto::naziv).containsExactly("foto.png", "dopis.pdf");
            assertThat(d.prilozi().get(0).href()).isEqualTo("/api/rn/" + RN + "/attachments/21");
            assertThat(d.brojeviNaIstojAdresi()).as("RB nije povučen").isEmpty();
        });
        verify(reissueGuard, never()).brojeviNaIstojAdresi(any());
    }

    /** T12: uz povučen RB stavke nose važeće brojeve istog iznajmljivača na istoj adresi (upozorenje). */
    @Test
    void list_onWithdrawnRn_carriesNumbersAtSameAddress() {
        SuspensionObjectionRepository.ObjectionSummary row = mock(SuspensionObjectionRepository.ObjectionSummary.class);
        when(row.getObjectionId()).thenReturn(objection.getObjectionId());
        when(row.getRn()).thenReturn(RN);
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(repository.findSummariesByRn(RN)).thenReturn(List.of(row));
        when(internalAccess.displayNames(any())).thenReturn(new java.util.HashMap<>());
        when(prilogService.zaVlasnike(any(), any())).thenReturn(java.util.Map.of());
        when(reissueGuard.brojeviNaIstojAdresi(RN)).thenReturn(List.of("HR120001000000000999"));

        assertThat(service.list(RN)).singleElement()
                .satisfies(d -> assertThat(d.brojeviNaIstojAdresi()).containsExactly("HR120001000000000999"));
    }

    @Test
    void list_unknownRn_is404() {
        when(rnRepository.findById(RN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(RN)).isInstanceOf(ResourceNotFoundException.class);
    }
}
