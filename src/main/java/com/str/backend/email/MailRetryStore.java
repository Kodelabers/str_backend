package com.str.backend.email;

import com.str.backend.egop.EgopRetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bilježi ishod slanja obavijesti iznajmljivaču u {@code str_rn.mail_retry}.
 *
 * <p>Svaka metoda ima <b>vlastitu kratku transakciju</b> ({@code REQUIRES_NEW}): zovu je slušatelji
 * u {@code AFTER_COMMIT} fazi, gdje je izvorna transakcija već dovršena, i {@link MailRetryJob},
 * koji transakciju namjerno ne drži preko SMTP-a. Isti obrazac kao {@code EgopFilingStore}.
 *
 * <p>Dok je mail ugašen ({@code app.mail.enabled=false}) neuspjeh se <b>ne bilježi</b>: poruka
 * nije pala, nego je namjerno nije bilo. Inače bi se uključivanjem maila iznajmljivačima odjednom
 * poslale sve obavijesti iz razdoblja kad je bio ugašen.
 *
 * <p>Backoff dijeli logiku s urudžbiranjem ({@link EgopRetryPolicy}), ali s vlastitim
 * postavkama {@code app.mail.retry.*}.
 */
@Component
public class MailRetryStore {

    private static final Logger log = LoggerFactory.getLogger(MailRetryStore.class);

    private final MailRetryRepository repository;
    private final MailProperties properties;
    private final Clock clock;
    private final EgopRetryPolicy policy;

    public MailRetryStore(MailRetryRepository repository,
                          MailProperties properties,
                          Clock clock,
                          @Value("${app.mail.retry.max-attempts:10}") int maxAttempts,
                          @Value("${app.mail.retry.backoff-base:PT5M}") Duration backoffBase,
                          @Value("${app.mail.retry.backoff-max:PT2H}") Duration backoffMax) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.policy = new EgopRetryPolicy(maxAttempts, backoffBase, backoffMax);
    }

    /** Slanje nije uspjelo — zakazuje sljedeći pokušaj, a nakon zadnjeg odustaje. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(MailRetryEntity.Kind kind, UUID refId, String reason) {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        MailRetryEntity existing = repository.findByKindAndRefId(kind, refId).orElse(null);
        if (existing == null) {
            // Unique (kind, ref_id) čuva od dvostrukog zapisa; utrka je praktički isključena jer
            // isti predmet ne obrađuju istodobno inline slanje i job.
            repository.save(MailRetryEntity.firstFailure(
                    kind, refId, now, policy.nextAttemptAt(now, 0), reason));
            log.warn("mail_retry_scheduled kind={} ref={} reason={}", kind, refId, reason);
            return;
        }
        if (!existing.isPending()) {
            return;
        }
        existing.recordFailure(now, policy.nextAttemptAt(now, existing.getAttempts()), reason);
        if (policy.isExhausted(existing.getAttempts())) {
            existing.abandon(now, "exhausted: " + reason);
            log.error("mail_retry_exhausted kind={} ref={} attempts={} reason={}"
                            + " — obavijest se više neće pokušavati automatski",
                    kind, refId, existing.getAttempts(), reason);
        } else {
            log.warn("mail_retry_failed kind={} ref={} attempt={} next_attempt_at={} reason={}",
                    kind, refId, existing.getAttempts(), existing.getNextAttemptAt(), reason);
        }
        repository.save(existing);
    }

    /** Poruka je poslana; zatvara zapis ako postoji (prvi pokušaj zapis ni nema). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(MailRetryEntity.Kind kind, UUID refId) {
        repository.findByKindAndRefId(kind, refId)
                .filter(MailRetryEntity::isPending)
                .ifPresent(e -> {
                    e.markSent(clock.instant());
                    repository.save(e);
                    log.info("mail_retry_sent kind={} ref={} attempts={}", kind, refId, e.getAttempts());
                });
    }

    /** Ponavljanje nema smisla (nema adrese, obavijest je zastarjela) — zatvara zapis ako postoji. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(MailRetryEntity.Kind kind, UUID refId, String reason) {
        repository.findByKindAndRefId(kind, refId)
                .filter(MailRetryEntity::isPending)
                .ifPresent(e -> {
                    e.abandon(clock.instant(), reason);
                    repository.save(e);
                    log.warn("mail_retry_abandoned kind={} ref={} reason={}", kind, refId, reason);
                });
    }

    /**
     * Čeka li obavijest u redu. Takvu obavijest šalje samo {@link MailRetryJob} — drugi put koji
     * je usput pokuša (npr. ponovno urudžbiranje) trošio bi pokušaje mimo backoffa.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isQueued(MailRetryEntity.Kind kind, UUID refId) {
        return repository.findByKindAndRefId(kind, refId).filter(MailRetryEntity::isPending).isPresent();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<MailRetryEntity> due(int batchSize) {
        return repository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                MailRetryEntity.Status.PENDING, clock.instant(), PageRequest.of(0, batchSize));
    }
}
