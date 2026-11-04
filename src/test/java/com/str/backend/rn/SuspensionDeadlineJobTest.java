package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.objection.SuspensionObjectionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Job radi nad svježe zaključanim RB-om, ne nad stanjem iz početnog upita: opoziv ili povlačenje
 * koje je stiglo u međuvremenu ne smije se prepisati suspenzijom.
 */
class SuspensionDeadlineJobTest {

    private static final String RN = "HR120001000000000001";

    private RnRepository rnRepository;
    private RnStatusTransitionService transitionService;
    private SuspensionObjectionRepository objectionRepository;
    private RegistrationNumberLogRepository logRepository;
    private SuspensionDeadlineJob job;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        transitionService = mock(RnStatusTransitionService.class);
        objectionRepository = mock(SuspensionObjectionRepository.class);
        logRepository = mock(RegistrationNumberLogRepository.class);
        job = new SuspensionDeadlineJob(rnRepository, transitionService, objectionRepository, logRepository);
    }

    @Test
    void suspendsProposalWithExpiredDeadline() {
        RnEntity rn = proposed(LocalDate.now().minusDays(1));
        when(rnRepository.findRnsWithSuspensionDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));

        job.run();

        verify(transitionService).transition(rn, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
                "SYSTEM", "Rok za rješavanje nepravilnosti istekao.");
    }

    @Test
    void skipsRnRevokedInTheMeantime() {
        RnEntity rn = proposed(LocalDate.now().minusDays(1));
        rn.applyStatus(RnStatus.REVOKED);
        when(rnRepository.findRnsWithSuspensionDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));

        job.run();

        verify(transitionService, never()).transition(any(), any(), any(), anyString(), anyString());
    }

    /**
     * Prigovor predan do kraja dana roka: stranka se očitovala u roku, pa o suspenziji prvo
     * odlučuje službenik — job RB preskače.
     */
    @Test
    void skipsRnWithObjectionSubmittedInTime() {
        LocalDate deadline = LocalDate.now().minusDays(1);
        RnEntity rn = proposed(deadline);
        when(rnRepository.findRnsWithSuspensionDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(objectionRepository.existsByRnAndStatusAndSubmittedAtBefore(
                RN, SuspensionObjectionStatus.SUBMITTED, SuspensionDeadlineJob.endOfDay(deadline)))
                .thenReturn(true);

        job.run();

        verify(transitionService, never()).transition(any(), any(), any(), anyString(), anyString());
    }

    /**
     * Prigovor predan nakon roka suspenziju ne zaustavlja: upit traži samo prigovor predan prije
     * kraja dana roka, pa ga kasni prigovor ne zadovoljava.
     */
    @Test
    void suspendsDespiteObjectionSubmittedAfterDeadline() {
        LocalDate deadline = LocalDate.now().minusDays(1);
        RnEntity rn = proposed(deadline);
        when(rnRepository.findRnsWithSuspensionDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(objectionRepository.existsByRnAndStatusAndSubmittedAtBefore(
                eq(RN), eq(SuspensionObjectionStatus.SUBMITTED), any())).thenReturn(false);

        job.run();

        verify(transitionService).transition(rn, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
                "SYSTEM", "Rok za rješavanje nepravilnosti istekao.");
    }

    /**
     * Zaostali prigovor na raniji prijedlog istog RB-a ne drži novi prijedlog zauvijek: job pita samo
     * za prigovor na trenutni prijedlog (zadnji prijelaz u SUSPENSION_PROPOSED).
     */
    @Test
    void objectionOnEarlierProposal_doesNotHoldCurrentProposal() {
        LocalDate deadline = LocalDate.now(ZoneId.of("Europe/Zagreb")).minusDays(1);
        RnEntity rn = proposed(deadline);
        RegistrationNumberLogEntity prijedlog = mock(RegistrationNumberLogEntity.class);
        UUID prijedlogId = UUID.randomUUID();
        Instant prijedlogAt = Instant.parse("2026-07-01T08:00:00Z");
        when(prijedlog.getLogId()).thenReturn(prijedlogId);
        when(prijedlog.getOccurredAt()).thenReturn(prijedlogAt);
        when(rnRepository.findRnsWithSuspensionDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, RnStatus.SUSPENSION_PROPOSED.name()))
                .thenReturn(Optional.of(prijedlog));
        // Postoji SUBMITTED prigovor u roku, ali na raniji prijedlog.
        when(objectionRepository.existsByRnAndStatusAndSubmittedAtBefore(any(), any(), any())).thenReturn(true);
        when(objectionRepository.existsSubmittedForProposal(RN, prijedlogId, prijedlogAt,
                SuspensionDeadlineJob.endOfDay(deadline))).thenReturn(false);

        job.run();

        verify(transitionService).transition(rn, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
                "SYSTEM", "Rok za rješavanje nepravilnosti istekao.");
    }

    @Test
    void objectionOnCurrentProposal_holdsSuspension() {
        LocalDate deadline = LocalDate.now(ZoneId.of("Europe/Zagreb")).minusDays(1);
        RnEntity rn = proposed(deadline);
        RegistrationNumberLogEntity prijedlog = mock(RegistrationNumberLogEntity.class);
        UUID prijedlogId = UUID.randomUUID();
        when(prijedlog.getLogId()).thenReturn(prijedlogId);
        when(rnRepository.findRnsWithSuspensionDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, RnStatus.SUSPENSION_PROPOSED.name()))
                .thenReturn(Optional.of(prijedlog));
        when(objectionRepository.existsSubmittedForProposal(eq(RN), eq(prijedlogId), any(), any())).thenReturn(true);

        job.run();

        verify(transitionService, never()).transition(any(), any(), any(), anyString(), anyString());
    }

    /** „Danas" joba je po hrvatskom vremenu (kontejner je UTC): upit roka dobiva datum u Europe/Zagreb. */
    @Test
    void today_isZagrebDate() {
        when(rnRepository.findRnsWithSuspensionDeadlineBefore(any())).thenReturn(List.of());

        job.run();

        verify(rnRepository).findRnsWithSuspensionDeadlineBefore(LocalDate.now(ZoneId.of("Europe/Zagreb")));
    }

    /** Kraj dana roka je ponoć po hrvatskom vremenu, ne po zoni poslužitelja. */
    @Test
    void endOfDay_isMidnightAfterDeadlineInZagreb() {
        assertThat(SuspensionDeadlineJob.endOfDay(LocalDate.of(2026, 7, 10)))
                .isEqualTo(Instant.parse("2026-07-10T22:00:00Z"));
        assertThat(SuspensionDeadlineJob.endOfDay(LocalDate.of(2026, 12, 10)))
                .isEqualTo(LocalDate.of(2026, 12, 11).atStartOfDay(ZoneId.of("Europe/Zagreb")).toInstant());
    }

    private static RnEntity proposed(LocalDate deadline) {
        RnEntity rn = RnEntity.issue(RN, UUID.randomUUID(), UUID.randomUUID(), LocalDate.now().minusYears(1));
        rn.applyStatus(RnStatus.SUSPENSION_PROPOSED);
        rn.setSuspensionDeadline(deadline);
        return rn;
    }
}
