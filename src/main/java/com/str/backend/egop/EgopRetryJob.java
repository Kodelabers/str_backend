package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.request.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ponovno urudžbira submissione koje eGOP dostava nije dovršila (transientni pad
 * eGOP-a, prekid usred toka). Aktivan samo kad je eGOP integracija uključena —
 * u local/mock/test okruženju (mock klijent) job se ne diže, pa ne dira seed podatke.
 *
 * <p>Retry je siguran jer je {@link EgopFilingService} resumabilan: spremljena
 * oznaka subjekta / identitet predmeta / {@code document_attached} flag sprječavaju
 * dupliciranje na ponovljenom pokušaju.
 */
@Component
@ConditionalOnProperty(name = "hr.infodom.str.integration.egop.enabled", havingValue = "true")
public class EgopRetryJob {

    private static final Logger log = LoggerFactory.getLogger(EgopRetryJob.class);

    private final SubmissionRepository submissionRepository;
    private final EgopPismenoRepository pismenoRepository;
    private final EgopRegistrationDispatcher dispatcher;
    private final EgopAktDispatcher aktDispatcher;
    private final EgopRetryPolicy retryPolicy;
    private final Duration grace;
    private final Duration maxAge;
    private final int batchSize;

    public EgopRetryJob(SubmissionRepository submissionRepository,
                        EgopPismenoRepository pismenoRepository,
                        EgopRegistrationDispatcher dispatcher,
                        EgopAktDispatcher aktDispatcher,
                        EgopRetryPolicy retryPolicy,
                        @Value("${str.egop.retry.grace:PT5M}") Duration grace,
                        @Value("${str.egop.retry.max-age:P7D}") Duration maxAge,
                        @Value("${str.egop.retry.batch-size:50}") int batchSize) {
        this.submissionRepository = submissionRepository;
        this.pismenoRepository = pismenoRepository;
        this.dispatcher = dispatcher;
        this.aktDispatcher = aktDispatcher;
        this.retryPolicy = retryPolicy;
        this.grace = grace;
        this.maxAge = maxAge;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${str.egop.retry.cron:0 */2 * * * *}")
    public void retryPending() {
        Instant now = Instant.now();
        retryRegistracije(now);
        retryAkte(now);
    }

    private void retryRegistracije(Instant now) {
        List<UUID> candidates = submissionRepository.findEgopRetryCandidates(
                EgopSyncStatus.SYNCED, retryPolicy.maxAttempts(), now.minus(grace), now.minus(maxAge), now);
        if (candidates.isEmpty()) {
            return;
        }
        List<UUID> batch = batch(candidates);
        log.info("egop_retry_start candidates={} batch={}", candidates.size(), batch.size());
        int ok = 0;
        int failed = 0;
        for (UUID submissionId : batch) {
            try {
                dispatcher.dispatch(submissionId);
                ok++;
            } catch (RuntimeException e) {
                // dispatcher sam hvata i bilježi svoje greške; ovo je zadnja brana
                failed++;
                log.error("egop_retry_dispatch_error submission={}: {}", submissionId, e.getMessage(), e);
            }
        }
        log.info("egop_retry_done processed={} ok_or_failed_marked={} unexpected_errors={}",
                batch.size(), ok, failed);
    }

    /**
     * Akti životnog ciklusa imaju vlastiti red kandidata jer su vlastita jedinica posla —
     * pad suspenzije ne smije dirati status registracije na submissionu, ni obrnuto.
     *
     * <p>{@code grace} vrijedi i ovdje: akt koji još nije ni pokušan preskače se dok ne
     * odstoji, da cron ne otme posao inline slanju koje je pokrenuo listener. Akt koji je
     * pao ima {@code nextAttemptAt} i taj ga rok pušta ranije.
     */
    private void retryAkte(Instant now) {
        List<UUID> candidates = pismenoRepository.findRetryCandidates(
                EgopSyncStatus.SYNCED, EgopPismenoEntity.ACT_REF_REGISTRACIJA,
                retryPolicy.maxAttempts(), now.minus(grace), now.minus(maxAge), now);
        if (candidates.isEmpty()) {
            return;
        }
        List<UUID> batch = batch(candidates);
        log.info("egop_akt_retry_start candidates={} batch={}", candidates.size(), batch.size());
        int failed = 0;
        for (UUID aktId : batch) {
            try {
                aktDispatcher.dispatch(aktId);
            } catch (RuntimeException e) {
                failed++;
                log.error("egop_akt_retry_error akt={}: {}", aktId, e.getMessage(), e);
            }
        }
        log.info("egop_akt_retry_done processed={} unexpected_errors={}", batch.size(), failed);
    }

    private List<UUID> batch(List<UUID> candidates) {
        return candidates.size() > batchSize ? candidates.subList(0, batchSize) : candidates;
    }
}
