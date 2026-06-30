package com.str.backend.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * STR-3.3: scheduled auto-purge of accommodation activity older than the 18-month retention
 * (čl. 5. st. 5, čl. 9 STR Uredbe). Delegates to {@link AccommodationActivityService#purgeExpired()},
 * which is also reachable manually via {@code DELETE /api/activity/purge} and writes the audit row.
 */
@Component
public class AccommodationActivityPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AccommodationActivityPurgeJob.class);

    private final AccommodationActivityService service;

    public AccommodationActivityPurgeJob(AccommodationActivityService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.activity.purge-cron:0 0 4 * * *}")
    public void purgeExpired() {
        int removed = service.purgeExpired();
        if (removed > 0) {
            log.info("activity_purge_job removed={}", removed);
        }
    }
}
