package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
 * Job radi nad svježe zaključanim RB-om, ne nad stanjem iz početnog upita: opoziv ili povlačenje
 * koje je stiglo u međuvremenu ne smije se prepisati suspenzijom.
 */
class SuspensionDeadlineJobTest {

    private static final String RN = "HR120001000000000001";

    private RnRepository rnRepository;
    private RnStatusTransitionService transitionService;
    private SuspensionDeadlineJob job;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        transitionService = mock(RnStatusTransitionService.class);
        job = new SuspensionDeadlineJob(rnRepository, transitionService);
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

    private static RnEntity proposed(LocalDate deadline) {
        RnEntity rn = RnEntity.issue(RN, UUID.randomUUID(), UUID.randomUUID(), LocalDate.now().minusYears(1));
        rn.applyStatus(RnStatus.SUSPENSION_PROPOSED);
        rn.setSuspensionDeadline(deadline);
        return rn;
    }
}
