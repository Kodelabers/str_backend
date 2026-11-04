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
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
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
        service = new RnService(repository, transitionService, accommodationRepository,
                accommodationTypeRepository, clock, new DocumentLabels(), prilogService);
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

        service.suspend(rn.getRn(), RnTrigger.INCOMPLETE_DOCUMENTATION, DEADLINE, null, null);

        verify(transitionService).transition(
                rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INCOMPLETE_DOCUMENTATION, null, null);
    }

    /**
     * Rok je obvezan (MINTS): bez njega kolona ostaje NULL, {@code SuspensionDeadlineJob} predmet
     * nikad ne pokupi i RB zauvijek visi u SUSPENSION_PROPOSED. Sustav ga više ne određuje sam.
     */
    @Test
    void suspend_requiresDeadline() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.INSPECTION, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.deadline.required");
        verify(repository, never()).findForUpdate(anyString());
        verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    }

    /** Povijesni razlog ostaje u enumu, ali novi prijedlog njime više ne nastaje. */
    @Test
    void suspend_rejectsHistoricalConsentExpiry() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.CONSENT_EXPIRY,
                DEADLINE, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.trigger.invalid");
    }

    @Test
    void suspend_keepsExplicitDeadline() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        LocalDate chosen = LocalDate.of(2026, 6, 1);

        service.suspend(rn.getRn(), RnTrigger.INSPECTION, chosen, null, null);

        assertThat(rn.getSuspensionDeadline()).isEqualTo(chosen);
    }

    /** Tipfeler u godini ne smije proći kao valjan poziv na očitovanje (čl. 30. st. 2 ZUP-a). */
    @Test
    void suspend_rejectsDeadlineInThePast() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.INSPECTION,
                LocalDate.now(clock).minusDays(1), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.deadline.past");
        verify(transitionService, never())
                .transition(any(), any(), any(), anyString(), anyString());
    }

    /** Rok koji ističe danas je valjan — job hvata samo strogo starije od danas. */
    @Test
    void suspend_acceptsDeadlineToday() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));
        LocalDate today = LocalDate.now(clock);

        service.suspend(rn.getRn(), RnTrigger.INSPECTION, today, null, null);

        assertThat(rn.getSuspensionDeadline()).isEqualTo(today);
    }

    @Test
    void suspend_requiresNote_forOtherReason() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.OTHER, DEADLINE, "  ", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.note.required");
        verify(transitionService, never())
                .transition(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void suspend_rejectsNonSuspendTrigger() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.WITHDRAWAL, DEADLINE, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.trigger.invalid");
        verify(transitionService, never())
                .transition(any(), any(), any(), anyString(), anyString());
    }

    /** Kodirani razlog ide u trag kao natpis — akt povlačenja ga odande ispisuje. Okidač ostaje WITHDRAWAL. */
    @Test
    void withdraw_logsLabelOfCodedReason() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        service.withdraw(rn.getRn(), WithdrawalReason.INVALID_INFORMATION, "ne upisuje se", null);

        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL,
                null, "dostavljene su nevjerodostojne ili nevaljane informacije");
    }

    /** Uz „drugi razlozi" u trag (i akt) ide opis službenika. */
    @Test
    void withdraw_logsNoteForOtherReason() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        service.withdraw(rn.getRn(), WithdrawalReason.OTHER, "  nedostaje suglasnost  ", null);

        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL,
                null, "nedostaje suglasnost");
    }

    @Test
    void withdraw_requiresReason() {
        assertThatThrownBy(() -> service.withdraw("HR120001000000000001", null, "opis", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.withdraw.reason.required");
        verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    }

    @Test
    void withdraw_requiresNote_forOtherReason() {
        assertThatThrownBy(() -> service.withdraw("HR120001000000000001", WithdrawalReason.OTHER, "  ", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.withdraw.note.required");
        verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    }

    /** Neuvaženi prigovor nakon isteka roka: odmah suspenzija, u ime službenika, istim prijelazom kao job. */
    @Test
    void suspendIfDeadlineExpired_suspendsInOfficialsName() {
        RnEntity rn = proposedRn(LocalDate.now(clock).minusDays(1));
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThat(service.suspendIfDeadlineExpired(rn.getRn(), "INTERNAL:12345678903")).isTrue();

        verify(transitionService, times(1)).transition(rn, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
                "INTERNAL:12345678903", SuspensionDeadlineJob.DEADLINE_EXCEEDED_NOTE);
    }

    /** Rok koji ističe danas još teče — kao i u jobu. */
    @Test
    void suspendIfDeadlineExpired_keepsProposalWhileDeadlineRuns() {
        RnEntity rn = proposedRn(LocalDate.now(clock));
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThat(service.suspendIfDeadlineExpired(rn.getRn(), "INTERNAL:12345678903")).isFalse();

        verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    }

    @Test
    void suspendIfDeadlineExpired_ignoresRnNoLongerProposed() {
        RnEntity rn = activeRn();
        when(repository.findForUpdate(rn.getRn())).thenReturn(Optional.of(rn));

        assertThat(service.suspendIfDeadlineExpired(rn.getRn(), "INTERNAL:12345678903")).isFalse();

        verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    }

    /** STR-1.5: nevažeći za oglašavanje su i opozvani i povučeni, ne samo suspendirani. */
    @Test
    void inactive_listsSuspendedRevokedAndWithdrawn() {
        service.inactive(null);

        verify(repository).findByStatusInOrderByUpdatedAtDesc(
                List.of(RnStatus.SUSPENDED, RnStatus.REVOKED, RnStatus.WITHDRAWN));
    }

    /** Županijski službenik: isti statusi, samo smještaji njegove županije. */
    @Test
    void inactive_withScope_filtersByCounty() {
        List<String> scope = List.of("Splitsko-dalmatinska županija", "Splitsko-dalmatinska");

        service.inactive(scope);

        verify(repository).findByStatusInAndCountyIn(
                List.of(RnStatus.SUSPENDED, RnStatus.REVOKED, RnStatus.WITHDRAWN), scope);
        verify(repository, never()).findByStatusInOrderByUpdatedAtDesc(any());
    }

    private RnEntity activeRn() {
        return RnEntity.issue("HR120001000000000001", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 4, 30));
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

        service.withdraw(rn.getRn(), WithdrawalReason.OTHER, "razlog", actor);
        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL, actor, "razlog");

        service.suspend(rn.getRn(), RnTrigger.INSPECTION, DEADLINE, null, actor);
        verify(transitionService).transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INSPECTION, actor, null);

        service.revokeProposal(rn.getRn(), actor);
        verify(transitionService).transition(rn, RnStatus.ACTIVE, RnTrigger.REVOKE_PROPOSAL, actor, null);

        service.reactivate(rn.getRn(), actor);
        verify(transitionService).transition(rn, RnStatus.ACTIVE, RnTrigger.REACTIVATE, actor, null);
    }

    // ---- T17: prilozi službenika uz prijedlog suspenzije i povlačenje

    /** Prilozi se vežu uz upravo nastali zapis prijelaza, pod službenikom koji je radnju napravio. */
    @Test
    void suspend_withAttachments_bindsToNewLogEntry() {
        RnEntity rn = mock(RnEntity.class);
        when(repository.findForUpdate("HR00000001")).thenReturn(Optional.of(rn));
        UUID logId = UUID.randomUUID();
        when(transitionService.transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INSPECTION,
                "INTERNAL:12345678903", null)).thenReturn(logId);

        service.suspend("HR00000001", RnTrigger.INSPECTION, DEADLINE, null, "INTERNAL:12345678903", List.of(7L, 8L));

        verify(prilogService).bind("INTERNAL:12345678903", List.of(7L, 8L), PrilogVlasnik.prijelaz(logId));
    }

    @Test
    void withdraw_withAttachments_bindsToNewLogEntry() {
        RnEntity rn = mock(RnEntity.class);
        when(repository.findForUpdate("HR00000001")).thenReturn(Optional.of(rn));
        UUID logId = UUID.randomUUID();
        when(transitionService.transition(eq(rn), eq(RnStatus.WITHDRAWN), eq(RnTrigger.WITHDRAWAL),
                eq("INTERNAL:12345678903"), anyString())).thenReturn(logId);

        service.withdraw("HR00000001", WithdrawalReason.DATA_NOT_CORRECTED, null, "INTERNAL:12345678903",
                List.of(9L));

        verify(prilogService).bind("INTERNAL:12345678903", List.of(9L), PrilogVlasnik.prijelaz(logId));
    }

    /** Bez priloga nema vezanja — ni poziva sa praznim popisom. */
    @Test
    void suspend_withoutAttachments_doesNotBind() {
        RnEntity rn = mock(RnEntity.class);
        when(repository.findForUpdate("HR00000001")).thenReturn(Optional.of(rn));

        service.suspend("HR00000001", RnTrigger.INSPECTION, DEADLINE, null, "INTERNAL:12345678903");

        verify(prilogService, never()).bind(any(), any(), any());
    }

    /** Neispravan prilog ruši radnju: iznimka iz vezanja izlazi iz transakcijske metode (rollback). */
    @Test
    void suspend_rejectedAttachment_propagates() {
        RnEntity rn = mock(RnEntity.class);
        when(repository.findForUpdate("HR00000001")).thenReturn(Optional.of(rn));
        when(transitionService.transition(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(prilogService.bind(any(), any(), any())).thenThrow(new PrilogException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "error.attachment.totalTooLarge",
                PrilogException.TOTAL_TOO_LARGE));

        assertThatThrownBy(() -> service.suspend("HR00000001", RnTrigger.INSPECTION, DEADLINE, null,
                "INTERNAL:12345678903", List.of(1L)))
                .isInstanceOfSatisfying(PrilogException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ATTACHMENT_TOTAL_TOO_LARGE"));
    }
}
