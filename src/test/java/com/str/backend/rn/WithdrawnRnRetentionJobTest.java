package com.str.backend.rn;

import com.str.backend.admin.AdminAuditService;
import com.str.backend.domain.RnStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WithdrawnRnRetentionJobTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneId.of("UTC"));

    @Test
    void detect_usesCutoff18MonthsBack_andAuditsWhenDue() {
        RnRepository repo = mock(RnRepository.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        LocalDate expectedCutoff = LocalDate.of(2026, 6, 29).minusMonths(18); // 2024-12-29
        when(repo.findByStatusAndValidToBefore(RnStatus.WITHDRAWN, expectedCutoff))
                .thenReturn(List.of(withdrawn(), withdrawn()));

        new WithdrawnRnRetentionJob(repo, audit, clock, 18).detectDueForRetention();

        verify(repo).findByStatusAndValidToBefore(RnStatus.WITHDRAWN, expectedCutoff);
        verify(audit).record(eq(AdminAuditService.SYSTEM), eq("RETENTION_DUE"), eq("RN"), any(), any());
    }

    @Test
    void detect_noAudit_whenNothingDue() {
        RnRepository repo = mock(RnRepository.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        when(repo.findByStatusAndValidToBefore(any(), any())).thenReturn(List.of());

        new WithdrawnRnRetentionJob(repo, audit, clock, 18).detectDueForRetention();

        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    private RnEntity withdrawn() {
        return RnEntity.issue("HR12000100000000000" + (counter++), UUID.randomUUID(),
                UUID.randomUUID(), LocalDate.of(2024, 1, 1));
    }

    private int counter = 1;
}
