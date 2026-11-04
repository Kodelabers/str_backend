package com.str.backend.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Noćno briše nevezane priloge starije od {@code app.attachments.staged-ttl}: korisnik ih je
 * učitao, ali radnju nije poslao (zatvorio formu, odustao). Vezani prilozi su dio spisa i ostaju.
 */
@Component
public class PrilogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PrilogCleanupJob.class);

    private final PrilogRepository repository;
    private final Duration ttl;

    public PrilogCleanupJob(PrilogRepository repository,
                            @Value("${app.attachments.staged-ttl:PT24H}") Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    @Scheduled(cron = "${app.attachments.cleanup-cron:0 45 3 * * *}")
    public void purgeStaged() {
        int deleted = repository.deleteStagedBefore(Instant.now().minus(ttl));
        if (deleted > 0) {
            log.info("Attachment cleanup: purged {} unbound attachments older than {}", deleted, ttl);
        }
    }
}
