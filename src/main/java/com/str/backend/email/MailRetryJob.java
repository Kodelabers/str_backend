package com.str.backend.email;

import com.str.backend.egop.EgopRegistrationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Ponovno slanje obavijesti iznajmljivaču koje nisu prošle ({@code str_rn.mail_retry}).
 *
 * <p>Radi samo dok je mail uključen — dok je ugašen, {@link MailRetryStore} ionako ništa ne
 * bilježi. Svaka obavijest ide kroz isti put kao i prvi pokušaj, pa job sam ne zna ništa o
 * sadržaju poruke; ishod (poslano / novi neuspjeh / odustajanje) bilježi taj put.
 *
 * <p><b>Bez transakcije</b> preko slanja: zapisi se čitaju i pišu kroz kratke transakcije
 * {@link MailRetryStore}-a, a SMTP čekanje je izvan njih.
 *
 * <p>Obavijest starija od {@code app.mail.retry.max-age} se više ne šalje — iznajmljivaču
 * tjedan dana kasno stigla obavijest o suspenziji više zbunjuje nego pomaže, a do tada je
 * ionako vidio stanje u aplikaciji.
 */
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true", matchIfMissing = true)
public class MailRetryJob {

    private static final Logger log = LoggerFactory.getLogger(MailRetryJob.class);

    private final MailRetryStore store;
    private final EgopRegistrationDispatcher registrationDispatcher;
    private final RnLifecycleEmailListener lifecycleListener;
    private final Clock clock;
    private final Duration maxAge;
    private final int batchSize;

    public MailRetryJob(MailRetryStore store,
                        EgopRegistrationDispatcher registrationDispatcher,
                        RnLifecycleEmailListener lifecycleListener,
                        Clock clock,
                        @Value("${app.mail.retry.max-age:P3D}") Duration maxAge,
                        @Value("${app.mail.retry.batch-size:50}") int batchSize) {
        this.store = store;
        this.registrationDispatcher = registrationDispatcher;
        this.lifecycleListener = lifecycleListener;
        this.clock = clock;
        this.maxAge = maxAge;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.mail.retry.cron:0 */5 * * * *}")
    public void retryDue() {
        List<MailRetryEntity> due = store.due(batchSize);
        if (due.isEmpty()) {
            return;
        }
        Instant cutoff = clock.instant().minus(maxAge);
        log.info("mail_retry_start due={}", due.size());
        int unexpected = 0;
        for (MailRetryEntity zapis : due) {
            if (zapis.getCreatedAt().isBefore(cutoff)) {
                store.abandon(zapis.getKind(), zapis.getRefId(), "expired");
                continue;
            }
            try {
                switch (zapis.getKind()) {
                    case RN_ISSUED -> registrationDispatcher.retryEmail(zapis.getRefId());
                    case RN_LIFECYCLE -> lifecycleListener.retry(zapis.getRefId());
                }
            } catch (RuntimeException e) {
                // Put slanja sam bilježi svoje ishode; ovo je pad prije toga (npr. render
                // predloška) — računa se kao neuspjeli pokušaj, da zapis ne ostane zaglavljen.
                unexpected++;
                log.error("mail_retry_error kind={} ref={}: {}",
                        zapis.getKind(), zapis.getRefId(), e.getMessage(), e);
                store.recordFailure(zapis.getKind(), zapis.getRefId(), "error: " + e.getMessage());
            }
        }
        log.info("mail_retry_done processed={} unexpected_errors={}", due.size(), unexpected);
    }
}
