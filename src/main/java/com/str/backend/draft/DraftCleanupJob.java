package com.str.backend.draft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Component
public class DraftCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DraftCleanupJob.class);

    private final SubmissionDraftRepository repository;
    private final int ttlDays;

    public DraftCleanupJob(SubmissionDraftRepository repository,
                           @Value("${app.draft.ttl-days:30}") int ttlDays) {
        this.repository = repository;
        this.ttlDays = ttlDays;
    }

    @Scheduled(cron = "${app.draft.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(ttlDays));
        long deleted = repository.deleteByUpdatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Draft cleanup: purged {} drafts older than {} days", deleted, ttlDays);
        }
    }
}
