package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.objection.SuspensionObjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T12: noćno povlačenje istekom roka, po uzoru na {@link SuspensionDeadlineJobTest}. Posao radi nad
 * svježe zaključanim RB-om i preskače broj uz očitovanje predano u roku na trenutni prijedlog.
 */
class WithdrawalDeadlineJobTest {

    private static final String RN = "HR120001000000000001";
    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    private RnRepository rnRepository;
    private RnStatusTransitionService transitionService;
    private SuspensionObjectionRepository objectionRepository;
    private RegistrationNumberLogRepository logRepository;
    private WithdrawalDeadlineJob job;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        transitionService = mock(RnStatusTransitionService.class);
        objectionRepository = mock(SuspensionObjectionRepository.class);
        logRepository = mock(RegistrationNumberLogRepository.class);
        job = new WithdrawalDeadlineJob(rnRepository, transitionService, objectionRepository, logRepository);
    }

    @Test
    void withdrawsProposalWithExpiredDeadline() {
        RnEntity rn = proposed(LocalDate.now(ZAGREB).minusDays(1));
        when(rnRepository.findRnsWithWithdrawalDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));

        job.run();

        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED,
                "SYSTEM", WithdrawalDeadlineJob.DEADLINE_EXCEEDED_NOTE);
    }

    /** Broj je u međuvremenu opozvan, obustavljen ili mu rok još teče — ostaje kakav jest. */
    @Test
    void skipsRnNoLongerProposedOrDeadlineRunning() {
        RnEntity revoked = proposed(LocalDate.now(ZAGREB).minusDays(1));
        revoked.applyStatus(RnStatus.REVOKED);
        RnEntity running = proposed(LocalDate.now(ZAGREB));
        when(rnRepository.findRnsWithWithdrawalDeadlineBefore(any())).thenReturn(List.of(RN, "HR2"));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(revoked));
        when(rnRepository.findForUpdate("HR2")).thenReturn(Optional.of(running));

        job.run();

        verify(transitionService, never()).transition(any(), any(), any(), anyString(), anyString());
    }

    /** Očitovanje na trenutni prijedlog predano do kraja dana roka: odlučuje službenik. */
    @Test
    void skipsRnWithObjectionOnCurrentProposalSubmittedInTime() {
        LocalDate deadline = LocalDate.now(ZAGREB).minusDays(1);
        RnEntity rn = proposed(deadline);
        RegistrationNumberLogEntity prijedlog = mock(RegistrationNumberLogEntity.class);
        UUID prijedlogId = UUID.randomUUID();
        Instant prijedlogAt = Instant.now().minusSeconds(86_400 * 10L);
        when(prijedlog.getLogId()).thenReturn(prijedlogId);
        when(prijedlog.getOccurredAt()).thenReturn(prijedlogAt);
        when(rnRepository.findRnsWithWithdrawalDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, "WITHDRAWAL_PROPOSED"))
                .thenReturn(Optional.of(prijedlog));
        when(objectionRepository.existsSubmittedForProposal(RN, prijedlogId, prijedlogAt,
                SuspensionDeadlineJob.endOfDay(deadline))).thenReturn(true);

        job.run();

        verify(transitionService, never()).transition(any(), any(), any(), anyString(), anyString());
    }

    /** Očitovanje predano nakon roka povlačenje ne zadržava: upit ga ne nalazi, pa posao povlači. */
    @Test
    void withdrawsDespiteObjectionSubmittedAfterDeadline() {
        LocalDate deadline = LocalDate.now(ZAGREB).minusDays(1);
        RnEntity rn = proposed(deadline);
        RegistrationNumberLogEntity prijedlog = mock(RegistrationNumberLogEntity.class);
        UUID prijedlogId = UUID.randomUUID();
        when(prijedlog.getLogId()).thenReturn(prijedlogId);
        when(prijedlog.getOccurredAt()).thenReturn(Instant.now().minusSeconds(86_400 * 10L));
        when(rnRepository.findRnsWithWithdrawalDeadlineBefore(any())).thenReturn(List.of(RN));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, "WITHDRAWAL_PROPOSED"))
                .thenReturn(Optional.of(prijedlog));
        // Upit traži samo prigovor predan prije kraja dana roka — kasni ga ne zadovoljava.
        when(objectionRepository.existsSubmittedForProposal(org.mockito.ArgumentMatchers.eq(RN),
                org.mockito.ArgumentMatchers.eq(prijedlogId), any(),
                org.mockito.ArgumentMatchers.eq(SuspensionDeadlineJob.endOfDay(deadline)))).thenReturn(false);

        job.run();

        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED,
                "SYSTEM", WithdrawalDeadlineJob.DEADLINE_EXCEEDED_NOTE);
    }

    private static RnEntity proposed(LocalDate deadline) {
        RnEntity rn = RnEntity.issue(RN, UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 1, 1));
        rn.applyStatus(RnStatus.WITHDRAWAL_PROPOSED);
        rn.setWithdrawalDeadline(deadline);
        return rn;
    }
}
