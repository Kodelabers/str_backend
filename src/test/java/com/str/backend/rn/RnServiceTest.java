package com.str.backend.rn;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.attachment.PrilogException;
import com.str.backend.attachment.PrilogService;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.document.DocumentLabels;
import com.str.backend.domain.WithdrawalReason;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.lookup.ObjectGroupPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RnServiceTest {

    private RnRepository repository;
    private RnStatusTransitionService transitionService;
    private AccommodationRepository accommodationRepository;
    private AccommodationTypeRepository accommodationTypeRepository;
    private PrilogService prilogService;
    private RnReissueGuard reissueGuard;
    private com.str.backend.objection.SuspensionObjectionRepository objectionRepository;
    private ObjectGroupPolicy objectGroupPolicy;
    private RegistrationNumberLogRepository logRepository;
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneId.of("UTC"));
    /** Valjan rok za očitovanje (u budućnosti u odnosu na {@link #clock}). */
    private static final LocalDate DEADLINE = LocalDate.of(2026, 5, 15);

    private RnService service;

    @BeforeEach
    void setUp() {
        repository = mock(RnRepository.class);
        transitionService = mock(RnStatusTransitionService.class);
        accommodationRepository = mock(AccommodationRepository.class);
        accommodationTypeRepository = mock(AccommodationTypeRepository.class);
        prilogService = mock(PrilogService.class);
        reissueGuard = mock(RnReissueGuard.class);
        objectionRepository = mock(com.str.backend.objection.SuspensionObjectionRepository.class);
        objectGroupPolicy = mock(ObjectGroupPolicy.class);
        logRepository = mock(RegistrationNumberLogRepository.class);
        service = new RnService(repository, transitionService, accommodationRepository,
                accommodationTypeRepository, clock, new DocumentLabels(), prilogService, reissueGuard,
                objectionRepository, objectGroupPolicy, logRepository,
                mock(com.str.backend.categorization.CategorizationDecisionRepository.class));
    }

    @Test
    void issue_returnsRnEntity_whenAccommodationHasNoType() {
        UUID submissionId = UUID.randomUUID();
        UUID accommodationId = UUID.randomUUID();
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, null)));
        when(repository.existsByRn(anyString())).thenReturn(false);

        RnEntity result = service.issue(submissionId, accommodationId);

        assertThat(result.getRn()).matches("HR\\d{18}");
        assertThat(result.getStatus()).isEqualTo(RnStatus.ACTIVE);
        assertThat(result.getIssueDate()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(result.getSubmissionId()).isEqualTo(submissionId);
        assertThat(result.getAccommodationId()).isEqualTo(accommodationId);
        verify(repository).save(result);
    }

    /** Postojeći eTurizam objekt smije biti bez adrese i kapaciteta — RB se izdaje i tada. */
    @Test
    void issue_returnsRnEntity_whenAccommodationHasNoAddressOrCapacity() {
        UUID accommodationId = UUID.randomUUID();
        AccommodationEntity bezPodataka = AccommodationEntity.create(
                UUID.randomUUID(), null, null, null, null,
                null, null, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, false);
        when(accommodationRepository.findById(accommodationId)).thenReturn(Optional.of(bezPodataka));
        when(repository.existsByRn(anyString())).thenReturn(false);

        RnEntity result = service.issue(UUID.randomUUID(), accommodationId);

        assertThat(result.getRn()).matches("HR\\d{18}");
        verify(repository).save(result);
    }

    /**
     * Kod županije u RB-u ne smije ovisiti o obliku naziva: lokalni seed piše bez „županija",
     * a naziv dopunjen iz eTurizma može biti velikim slovima.
     */
    @Test
    void issue_countyCodeIgnoresZupanijaSuffixAndCase() {
        UUID accommodationId = UUID.randomUUID();
        AccommodationEntity splitska = AccommodationEntity.create(
                UUID.randomUUID(), "SPLITSKO-DALMATINSKA", "Split", null, null,
                4, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, false);
        when(accommodationRepository.findById(accommodationId)).thenReturn(Optional.of(splitska));
        when(repository.existsByRn(anyString())).thenReturn(false);

        assertThat(service.issue(UUID.randomUUID(), accommodationId).getRn()).startsWith("HR18");
    }

    /** T13: oznaka skupine u RB-u (znamenke 5–6) je iz skupine vrste, ne tvrdo 00. */
    @Test
    void issue_groupCodeComesFromObjectGroupOfType() {
        UUID accommodationId = UUID.randomUUID();
        Long typeId = 7L;
        AccommodationEntity splitska = AccommodationEntity.create(
                UUID.randomUUID(), "Splitsko-dalmatinska", "Split", null, null,
                4, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, false);
        splitska.setAccommodationTypeId(typeId);
        when(accommodationRepository.findById(accommodationId)).thenReturn(Optional.of(splitska));
        when(accommodationTypeRepository.findById(typeId))
                .thenReturn(Optional.of(new AccommodationTypeEntity("Hoteli", true, "FT_HOTEL")));
        when(objectGroupPolicy.rbGroupCode(typeId)).thenReturn(3);
        when(repository.existsByRn(anyString())).thenReturn(false);

        assertThat(service.issue(UUID.randomUUID(), accommodationId).getRn()).startsWith("HR180307");
    }

    @Test
    void issue_returnsRnEntity_whenTypeAllowsRn() {
        UUID accommodationId = UUID.randomUUID();
        Long typeId = 1L;
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, typeId)));
        when(accommodationTypeRepository.findById(typeId))
                .thenReturn(Optional.of(new AccommodationTypeEntity("Apartman", true, "domacinstvo")));
        when(repository.existsByRn(anyString())).thenReturn(false);

        RnEntity result = service.issue(UUID.randomUUID(), accommodationId);

        assertThat(result.getRn()).matches("HR\\d{18}");
        verify(repository).save(result);
    }

    @Test
    void issue_throwsBusinessException_whenTypeDoesNotAllowRn() {
        UUID accommodationId = UUID.randomUUID();
        Long typeId = 2L;
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, typeId)));
        when(accommodationTypeRepository.findById(typeId))
                .thenReturn(Optional.of(new AccommodationTypeEntity("Hotel", false, "ostalo")));

        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), accommodationId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.type.not.allowed");
        verify(repository, never()).save(any());
    }

    @Test
    void issue_retriesUntilUniqueRn_thenSaves() {
        UUID accommodationId = UUID.randomUUID();
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, null)));
        // first 4 candidates collide, 5th succeeds
        when(repository.existsByRn(anyString()))
                .thenReturn(true, true, true, true, false);

        RnEntity result = service.issue(UUID.randomUUID(), accommodationId);

        assertThat(result.getRn()).matches("HR\\d{18}");
        verify(repository, times(5)).existsByRn(anyString());
        verify(repository).save(result);
    }

    @Test
    void issue_throwsBusinessException_whenAllAttemptsCollide() {
        UUID accommodationId = UUID.randomUUID();
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, null)));
        when(repository.existsByRn(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), accommodationId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.generation.failed");
        verify(repository, never()).save(any());
    }

    @Test
    void issue_throwsResourceNotFoundException_whenAccommodationMissing() {
        UUID accommodationId = UUID.randomUUID();
        when(accommodationRepository.findById(accommodationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), accommodationId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suspend_acceptsIncompleteDocumentation() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        service.suspend(rn.getRn(), RnTrigger.INCOMPLETE_DOCUMENTATION, DEADLINE, null, null, List.of());

        verify(transitionService).transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INCOMPLETE_DOCUMENTATION,
                null, "nepotpuni i/ili netočni podaci", null);
    }

    /**
     * L-note: uz kodirani razlog u trag ide natpis razloga, ne bilješka službenika — akt i obavijest
     * razlog čitaju odande. Bilješka ide samo uz OTHER.
     */
    @Test
    void suspend_codedTrigger_logsLabel_notNote() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        service.suspend(rn.getRn(), RnTrigger.INSPECTION, DEADLINE, "interna bilješka", "INTERNAL:12345678903",
                List.of());

        verify(transitionService).transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INSPECTION,
                "INTERNAL:12345678903", "nalaz inspekcijskog nadzora", null);
    }

    @Test
    void suspend_other_logsNote() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        service.suspend(rn.getRn(), RnTrigger.OTHER, DEADLINE, "  opis službenika ", "INTERNAL:12345678903",
                List.of());

        verify(transitionService).transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.OTHER,
                "INTERNAL:12345678903", "opis službenika", null);
    }

    /** L-null-actor: prilozi bez identificiranog službenika su 403 ATTACHMENT_FORBIDDEN, ne 500. */
    @Test
    void suspend_attachmentsWithoutActor_is403() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThatThrownBy(() -> service.suspend(rn.getRn(), RnTrigger.INSPECTION, DEADLINE, null, null, List.of(7L)))
                .isInstanceOfSatisfying(PrilogException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(ex.getCode()).isEqualTo(PrilogException.FORBIDDEN);
                });
        verify(prilogService, never()).bind(any(), any(), any());
    }

    /**
     * Rok je obvezan (MINTS): bez njega kolona ostaje NULL, {@code SuspensionDeadlineJob} predmet
     * nikad ne pokupi i RB zauvijek visi u SUSPENSION_PROPOSED. Sustav ga više ne određuje sam.
     */
    @Test
    void suspend_requiresDeadline() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.INSPECTION, null, null, null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.deadline.required");
        verify(repository, never()).findForUpdate(anyString());
        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    /** Povijesni razlog ostaje u enumu, ali novi prijedlog njime više ne nastaje. */
    @Test
    void suspend_rejectsHistoricalConsentExpiry() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.CONSENT_EXPIRY,
                DEADLINE, null, null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.trigger.invalid");
    }

    @Test
    void suspend_keepsExplicitDeadline() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        LocalDate chosen = LocalDate.of(2026, 6, 1);

        service.suspend(rn.getRn(), RnTrigger.INSPECTION, chosen, null, null, List.of());

        assertThat(rn.getSuspensionDeadline()).isEqualTo(chosen);
    }

    /** Tipfeler u godini ne smije proći kao valjan poziv na očitovanje (čl. 30. st. 2 ZUP-a). */
    @Test
    void suspend_rejectsDeadlineInThePast() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.INSPECTION,
                LocalDate.now(clock).minusDays(1), null, null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.deadline.past");
        verify(transitionService, never())
                .transition(any(), any(), any(), anyString(), anyString(), any());
    }

    /** Rok koji ističe danas je valjan — job hvata samo strogo starije od danas. */
    @Test
    void suspend_acceptsDeadlineToday() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        LocalDate today = LocalDate.now(clock);

        service.suspend(rn.getRn(), RnTrigger.INSPECTION, today, null, null, List.of());

        assertThat(rn.getSuspensionDeadline()).isEqualTo(today);
    }

    @Test
    void suspend_requiresNote_forOtherReason() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.OTHER, DEADLINE, "  ", null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.note.required");
        verify(transitionService, never())
                .transition(any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void suspend_rejectsNonSuspendTrigger() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.WITHDRAWAL, DEADLINE, null, null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.trigger.invalid");
        verify(transitionService, never())
                .transition(any(), any(), any(), anyString(), anyString(), any());
    }

    // ---- T12: prijedlog povlačenja, obustava, istek roka i uvaženi prigovor

    /** Prijedlog iz svakog stojećeg statusa: rok se pamti, a rok suspenzije briše (postupak suspenzije završava). */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RnStatus.class,
            names = {"ACTIVE", "SUSPENSION_PROPOSED", "SUSPENDED"})
    void proposeWithdrawal_setsDeadline(RnStatus from) {
        RnEntity rn = rnIn(from);
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        service.proposeWithdrawal(rn.getRn(), WithdrawalReason.INVALID_INFORMATION, DEADLINE, "ne upisuje se",
                "INTERNAL:12345678903", List.of());

        verify(transitionService).transition(rn, RnStatus.WITHDRAWAL_PROPOSED, RnTrigger.WITHDRAWAL_PROPOSAL,
                "INTERNAL:12345678903", "dostavljene su nevjerodostojne ili nevaljane informacije", null);
        assertThat(rn.getWithdrawalDeadline()).isEqualTo(DEADLINE);
    }

    /** Prijedlog iz SUSPENDED javno ostaje suspendiran; iz ostalih statusa ne (T12). */
    @Test
    void proposeWithdrawal_fromSuspended_marksPublicSuspension() {
        RnEntity fromSuspended = rnIn(RnStatus.SUSPENDED);
        RnEntity fromActive = activeRn();
        when(repository.findForUpdate(fromSuspended.getRn())).thenReturn(Optional.of(fromSuspended));

        service.proposeWithdrawal(fromSuspended.getRn(), WithdrawalReason.DATA_NOT_CORRECTED, DEADLINE, null, null,
                List.of());
        assertThat(fromSuspended.isWithdrawalFromSuspended()).isTrue();

        RnEntity other = RnEntity.issue("HR120001000000000002", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 4, 1));
        when(repository.findForUpdate(other.getRn())).thenReturn(Optional.of(other));
        service.proposeWithdrawal(other.getRn(), WithdrawalReason.DATA_NOT_CORRECTED, DEADLINE, null, null, List.of());
        assertThat(other.isWithdrawalFromSuspended()).isFalse();
        assertThat(fromActive.isWithdrawalFromSuspended()).isFalse();
    }

    /** Uz „drugi razlozi" u trag (i akt) ide opis službenika. */
    @Test
    void proposeWithdrawal_logsNoteForOtherReason() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        service.proposeWithdrawal(rn.getRn(), WithdrawalReason.OTHER, DEADLINE, "  nedostaje suglasnost  ",
                null, List.of());

        verify(transitionService).transition(rn, RnStatus.WITHDRAWAL_PROPOSED, RnTrigger.WITHDRAWAL_PROPOSAL,
                null, "nedostaje suglasnost", null);
    }

    @Test
    void proposeWithdrawal_requiresReason() {
        assertThatThrownBy(() -> service.proposeWithdrawal("HR120001000000000001", null, DEADLINE, "opis", null,
                List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.withdraw.reason.required");
        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void proposeWithdrawal_requiresNote_forOtherReason() {
        assertThatThrownBy(() -> service.proposeWithdrawal("HR120001000000000001", WithdrawalReason.OTHER, DEADLINE,
                "  ", null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.withdraw.note.required");
        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    /** Bilješka ide u registration_number_log.reason (1024 znaka): preduga je 400, ne 500 iz baze. */
    @Test
    void notes_areLimitedTo1024Characters_forSuspensionAndWithdrawal() {
        String tooLong = "x".repeat(RnService.MAX_NOTE_LENGTH + 1);
        assertThatThrownBy(() -> service.proposeWithdrawal("HR120001000000000001", WithdrawalReason.OTHER, DEADLINE,
                tooLong, null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.note.tooLong");
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.OTHER, DEADLINE, tooLong, null,
                List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.note.tooLong");
        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());

        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        String exact = "y".repeat(RnService.MAX_NOTE_LENGTH);
        service.proposeWithdrawal(rn.getRn(), WithdrawalReason.OTHER, DEADLINE, exact, null, List.of());
        verify(transitionService).transition(rn, RnStatus.WITHDRAWAL_PROPOSED, RnTrigger.WITHDRAWAL_PROPOSAL,
                null, exact, null);
    }

    @Test
    void proposeWithdrawal_requiresDeadline() {
        assertThatThrownBy(() -> service.proposeWithdrawal("HR120001000000000001",
                WithdrawalReason.DATA_NOT_CORRECTED, null, null, null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.withdraw.deadline.required");
        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    /** Rok po hrvatskom vremenu: danas vrijedi, jučer ne. */
    @Test
    void proposeWithdrawal_rejectsDeadlineInThePast_acceptsToday() {
        assertThatThrownBy(() -> service.proposeWithdrawal("HR120001000000000001",
                WithdrawalReason.DATA_NOT_CORRECTED, LocalDate.of(2026, 4, 29), null, null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.withdraw.deadline.past");

        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        service.proposeWithdrawal(rn.getRn(), WithdrawalReason.DATA_NOT_CORRECTED, LocalDate.of(2026, 4, 30),
                null, null, List.of());
        assertThat(rn.getWithdrawalDeadline()).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void proposeWithdrawal_withAttachments_bindsToNewLogEntry() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        UUID logId = UUID.randomUUID();
        when(transitionService.transition(eq(rn), eq(RnStatus.WITHDRAWAL_PROPOSED),
                eq(RnTrigger.WITHDRAWAL_PROPOSAL), eq("INTERNAL:12345678903"), anyString(), any())).thenReturn(logId);

        service.proposeWithdrawal(rn.getRn(), WithdrawalReason.DATA_NOT_CORRECTED, DEADLINE, null,
                "INTERNAL:12345678903", List.of(9L));

        verify(prilogService).bind("INTERNAL:12345678903", List.of(9L), PrilogVlasnik.prijelaz(logId));
    }

    /** Obustava uvijek vraća u ACTIVE — i kad je povlačenje predloženo iz (prijedloga) suspenzije. */
    @Test
    void revokeWithdrawalProposal_alwaysReturnsToActive() {
        RnEntity rn = withdrawalProposedRn(DEADLINE);
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        service.revokeWithdrawalProposal(rn.getRn(), "INTERNAL:12345678903");

        verify(transitionService).transition(rn, RnStatus.ACTIVE, RnTrigger.REVOKE_WITHDRAWAL_PROPOSAL,
                "INTERNAL:12345678903", null, null);
    }

    @Test
    void revokeWithdrawalProposal_outsideProposal_is409() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThatThrownBy(() -> service.revokeWithdrawalProposal(rn.getRn(), null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RN_NOT_WITHDRAWAL_PROPOSED"));
        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    /** Neuvaženo očitovanje nakon isteka roka: odmah povlačenje, istim prijelazom kao posao. */
    @Test
    void withdrawIfDeadlineExpired_withdrawsInOfficialsName() {
        RnEntity rn = withdrawalProposedRn(LocalDate.now(clock).minusDays(1));
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThat(service.withdrawIfDeadlineExpired(rn.getRn(), "INTERNAL:12345678903")).isTrue();

        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED,
                "INTERNAL:12345678903", WithdrawalDeadlineJob.DEADLINE_EXCEEDED_NOTE, null);
    }

    @Test
    void withdrawIfDeadlineExpired_keepsProposalWhileDeadlineRuns_andIgnoresOtherStatuses() {
        RnEntity running = withdrawalProposedRn(LocalDate.now(clock));
        when(repository.findForUpdate(running.getRn())).thenReturn(Optional.of(running));
        assertThat(service.withdrawIfDeadlineExpired(running.getRn(), null)).isFalse();

        RnEntity suspensionProposed = RnEntity.issue("HR120001000000000005", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 4, 1));
        suspensionProposed.applyStatus(RnStatus.SUSPENSION_PROPOSED);
        suspensionProposed.setSuspensionDeadline(LocalDate.now(clock).minusDays(3));
        when(repository.findForUpdate(suspensionProposed.getRn())).thenReturn(Optional.of(suspensionProposed));
        assertThat(service.withdrawIfDeadlineExpired(suspensionProposed.getRn(), null)).isFalse();

        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    /** Uvaženi prigovor na povučen broj: WITHDRAWN → ACTIVE kad objekt nema drugi važeći broj. */
    @Test
    void reinstate_returnsWithdrawnToActive() {
        RnEntity rn = rnIn(RnStatus.WITHDRAWN);
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        when(reissueGuard.noviBrojIzdan(rn)).thenReturn(false);

        service.reinstate(rn.getRn(), "INTERNAL:12345678903");

        verify(transitionService).transition(rn, RnStatus.ACTIVE, RnTrigger.REINSTATE, "INTERNAL:12345678903", null, null);
    }

    /** Za objekt je izdan drugi važeći broj → 409 OBJECTION_RN_REISSUED, bez prijelaza. */
    @Test
    void reinstate_rejectsWhenNewNumberIssued() {
        RnEntity rn = rnIn(RnStatus.WITHDRAWN);
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        when(reissueGuard.noviBrojIzdan(rn)).thenReturn(true);

        assertThatThrownBy(() -> service.reinstate(rn.getRn(), null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_REISSUED"));
        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    /** Neuvaženi prigovor nakon isteka roka: odmah suspenzija, u ime službenika, istim prijelazom kao job. */
    @Test
    void suspendIfDeadlineExpired_suspendsInOfficialsName() {
        RnEntity rn = proposedRn(LocalDate.now(clock).minusDays(1));
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThat(service.suspendIfDeadlineExpired(rn.getRn(), "INTERNAL:12345678903")).isTrue();

        verify(transitionService, times(1)).transition(rn, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
                "INTERNAL:12345678903", SuspensionDeadlineJob.DEADLINE_EXCEEDED_NOTE, null);
    }

    /** Rok koji ističe danas još teče — kao i u jobu. */
    @Test
    void suspendIfDeadlineExpired_keepsProposalWhileDeadlineRuns() {
        RnEntity rn = proposedRn(LocalDate.now(clock));
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThat(service.suspendIfDeadlineExpired(rn.getRn(), "INTERNAL:12345678903")).isFalse();

        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void suspendIfDeadlineExpired_ignoresRnNoLongerProposed() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThat(service.suspendIfDeadlineExpired(rn.getRn(), "INTERNAL:12345678903")).isFalse();

        verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any());
    }

    /** STR-1.5: nevažeći za oglašavanje su i opozvani i povučeni, ne samo suspendirani; prijedlozi nisu, osim iz suspenzije. */
    @Test
    void inactive_listsSuspendedRevokedAndWithdrawn() {
        RnEntity suspended = rnIn(RnStatus.SUSPENDED);
        RnEntity proposedFromSuspended = rnIn(RnStatus.SUSPENDED);
        proposedFromSuspended.applyStatus(RnStatus.WITHDRAWAL_PROPOSED);
        proposedFromSuspended.markWithdrawalFromSuspended();
        RnEntity proposedFromActive = withdrawalProposedRn(DEADLINE);
        when(repository.findByStatusInOrderByUpdatedAtDesc(INACTIVE_QUERY))
                .thenReturn(List.of(suspended, proposedFromSuspended, proposedFromActive));

        // Prijedlog povlačenja iz suspenzije javno ostaje suspendiran (T12); iz ACTIVE vrijedi.
        assertThat(service.inactive(null)).containsExactly(suspended, proposedFromSuspended);
    }

    private static final List<RnStatus> INACTIVE_QUERY = List.of(RnStatus.SUSPENDED, RnStatus.WITHDRAWAL_PROPOSED,
            RnStatus.REVOKED, RnStatus.WITHDRAWN);

    /** Županijski službenik: isti statusi, samo smještaji njegove županije. */
    @Test
    void inactive_withScope_filtersByCounty() {
        List<String> scope = List.of("Splitsko-dalmatinska županija", "Splitsko-dalmatinska");

        service.inactive(scope);

        verify(repository).findByStatusInAndCountyIn(INACTIVE_QUERY, scope);
        verify(repository, never()).findByStatusInOrderByUpdatedAtDesc(any());
    }

    private RnEntity activeRn() {
        return RnEntity.issue("HR120001000000000001", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 4, 30));
    }

    private RnEntity rnIn(RnStatus status) {
        RnEntity rn = activeRn();
        if (status != RnStatus.ACTIVE) {
            rn.applyStatus(status);
        }
        return rn;
    }

    private RnEntity withdrawalProposedRn(LocalDate deadline) {
        RnEntity rn = activeRn();
        rn.applyStatus(RnStatus.WITHDRAWAL_PROPOSED);
        rn.setWithdrawalDeadline(deadline);
        return rn;
    }

    private RnEntity proposedRn(LocalDate deadline) {
        RnEntity rn = activeRn();
        rn.applyStatus(RnStatus.SUSPENSION_PROPOSED);
        rn.setSuspensionDeadline(deadline);
        return rn;
    }

    private AccommodationEntity accommodation(UUID id, Long typeId) {
        AccommodationEntity e = AccommodationEntity.create(
                UUID.randomUUID(), "Grad Zagreb", "Zagreb", "Ilica", "1",
                2, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, true);
        e.setLocationDetails(null, null, null, null, null, null, typeId, null);
        // Override the generated accommodationId via reflection — entity uses UUID.randomUUID() internally
        try {
            var field = AccommodationEntity.class.getDeclaredField("accommodationId");
            field.setAccessible(true);
            field.set(e, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    /** Revizijski trag: tko je kao službenik pokrenuo prijelaz. */
    @Test
    void lifecycle_passesOfficialActorToTransition() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        String actor = "INTERNAL:12345678903";

        service.suspend(rn.getRn(), RnTrigger.INSPECTION, DEADLINE, null, actor, List.of());
        verify(transitionService).transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INSPECTION, actor,
                "nalaz inspekcijskog nadzora", null);

        service.revokeProposal(rn.getRn(), actor);
        verify(transitionService).transition(rn, RnStatus.ACTIVE, RnTrigger.REVOKE_PROPOSAL, actor, null, null);

        service.reactivate(rn.getRn(), actor);
        verify(transitionService).transition(rn, RnStatus.ACTIVE, RnTrigger.REACTIVATE, actor, null, null);
    }

    // ---- T17: prilozi službenika uz prijedlog suspenzije i povlačenje

    /** Prilozi se vežu uz upravo nastali zapis prijelaza, pod službenikom koji je radnju napravio. */
    @Test
    void suspend_withAttachments_bindsToNewLogEntry() {
        RnEntity rn = mock(RnEntity.class);
        when(repository.findForUpdate("HR00000001")).thenReturn(Optional.of(rn));
        UUID logId = UUID.randomUUID();
        when(transitionService.transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INSPECTION,
                "INTERNAL:12345678903", "nalaz inspekcijskog nadzora", null)).thenReturn(logId);

        service.suspend("HR00000001", RnTrigger.INSPECTION, DEADLINE, null, "INTERNAL:12345678903", List.of(7L, 8L));

        verify(prilogService).bind("INTERNAL:12345678903", List.of(7L, 8L), PrilogVlasnik.prijelaz(logId));
    }

    /** Bez priloga nema vezanja — ni poziva sa praznim popisom. */
    @Test
    void suspend_withoutAttachments_doesNotBind() {
        RnEntity rn = mock(RnEntity.class);
        when(repository.findForUpdate("HR00000001")).thenReturn(Optional.of(rn));

        service.suspend("HR00000001", RnTrigger.INSPECTION, DEADLINE, null, "INTERNAL:12345678903", List.of());

        verify(prilogService, never()).bind(any(), any(), any());
    }

    /** Neispravan prilog ruši radnju: iznimka iz vezanja izlazi iz transakcijske metode (rollback). */
    @Test
    void suspend_rejectedAttachment_propagates() {
        RnEntity rn = mock(RnEntity.class);
        when(repository.findForUpdate("HR00000001")).thenReturn(Optional.of(rn));
        when(transitionService.transition(any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(prilogService.bind(any(), any(), any())).thenThrow(new PrilogException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "error.attachment.totalTooLarge",
                PrilogException.TOTAL_TOO_LARGE));

        assertThatThrownBy(() -> service.suspend("HR00000001", RnTrigger.INSPECTION, DEADLINE, null,
                "INTERNAL:12345678903", List.of(1L)))
                .isInstanceOfSatisfying(PrilogException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ATTACHMENT_TOTAL_TOO_LARGE"));
    }
}
