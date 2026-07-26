package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validira JPQL i logiku odabira kandidata za {@link EgopRetryJob} protiv prave baze
 * (H2) — retry job unit test mocka repozitorij, pa bi tipfeler u @Query prošao nezapažen.
 */
@SpringBootTest
@ActiveProfiles("test")
class EgopRetryCandidateQueryTest {

    @Autowired
    private SubmissionRepository submissionRepository;

    @Test
    void findEgopRetryCandidates_selectsUnsyncedWithinLimits() {
        UUID pending = save(EgopSyncStatus.NEW, 0, null);
        UUID failedRetryable = save(EgopSyncStatus.FAILED, 3, Instant.now().minus(1, ChronoUnit.MINUTES));
        UUID synced = save(EgopSyncStatus.SYNCED, 0, null);
        UUID exhausted = save(EgopSyncStatus.FAILED, 10, Instant.now().minus(1, ChronoUnit.MINUTES));

        List<UUID> candidates = candidates();

        assertThat(candidates).contains(pending, failedRetryable);
        assertThat(candidates).doesNotContain(synced, exhausted);
    }

    /** Backoff: dok nije došlo vrijeme sljedećeg pokušaja, submission nije kandidat. */
    @Test
    void findEgopRetryCandidates_skipsSubmissionsInBackoff() {
        UUID inBackoff = save(EgopSyncStatus.FAILED, 1, Instant.now().plus(1, ChronoUnit.HOURS));
        UUID dueNow = save(EgopSyncStatus.FAILED, 1, Instant.now().minus(1, ChronoUnit.MINUTES));

        List<UUID> candidates = candidates();

        assertThat(candidates).contains(dueNow);
        assertThat(candidates).doesNotContain(inBackoff);
    }

    private List<UUID> candidates() {
        Instant now = Instant.now();
        return submissionRepository.findEgopRetryCandidates(
                EgopSyncStatus.SYNCED, 10,
                now.plus(1, ChronoUnit.HOURS),   // notAfter — sve stvoreno prije
                now.minus(1, ChronoUnit.HOURS),  // notBefore — sve unutar prozora
                now);
    }

    private UUID save(EgopSyncStatus status, int attempts, Instant nextAttemptAt) {
        SubmissionEntity s = SubmissionEntity.create(null, UUID.randomUUID(), null, null, null, null);
        if (status == EgopSyncStatus.SYNCED) {
            s.applyEgopSyncStatus(EgopSyncStatus.SYNCED);
        } else if (status == EgopSyncStatus.FAILED) {
            for (int i = 0; i < attempts; i++) {
                s.markEgopFailed("boom", nextAttemptAt);
            }
        }
        return submissionRepository.save(s).getSubmissionId();
    }
}
