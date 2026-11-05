package com.str.backend.str.verifikacija;

import com.str.backend.egop.EgopRetryPolicy;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbox zahtjeva za žurnu verifikaciju ({@code str_rn.eturizam_verifikacija}).
 *
 * <p>{@link #zabiljezi} se zove iz transakcije izdavanja RB-a i <b>priključuje joj se</b>: redak
 * i RB nastaju ili ne nastaju zajedno, pa nakon pada između commita i upisa u eTurizam ništa ne
 * ostaje zaboravljeno. Sam upis u {@code str.*} tu se nikad ne radi — tek nakon commita
 * ({@link EturizamVerifikacijaListener}) ili iz {@link EturizamVerifikacijaRetryJob}-a.
 *
 * <p>Bilježenje neuspjeha i čitanje dospjelih redaka imaju vlastitu kratku transakciju
 * ({@code REQUIRES_NEW}), kao {@code MailRetryStore}: neuspjeli pokušaj je svoju transakciju
 * upravo poništio. Backoff dijeli logiku s urudžbiranjem ({@link EgopRetryPolicy}), s vlastitim
 * postavkama {@code str.eturizam.verifikacija.retry.*}.
 */
@Component
public class VerifikacijaOutbox {

    private static final Logger log = LoggerFactory.getLogger(VerifikacijaOutbox.class);

    private final VerifikacijaOutboxRepository repository;
    private final Clock clock;
    private final EgopRetryPolicy policy;
    private final EturizamVerifikacijaProperties.Retry retry;

    public VerifikacijaOutbox(VerifikacijaOutboxRepository repository,
                              Clock clock,
                              EturizamVerifikacijaProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.retry = properties.retry();
        this.policy = new EgopRetryPolicy(retry.maxAttempts(), retry.backoffBase(), retry.backoffMax());
    }

    /**
     * Bilježi da eTurizmu treba upisati zahtjev za žurnu verifikaciju — samo kad je RB izdan za
     * objekt iz eTurizma koji u tom trenutku <b>nije verificiran</b> ({@code verified = false}).
     * Novi objekt ({@code facility == null}) i verificiran objekt ne ostavljaju trag. Redak nastaje
     * i dok je integracija ugašena: jeftin je, a pokazuje što je čekalo.
     *
     * <p>{@link Propagation#MANDATORY}: redak mora nastati u transakciji koja izdaje RB, nikad sam.
     *
     * <p>Bez {@code documentId} zahtjev se ne može upisati ({@code source_document_id} je NOT NULL),
     * pa redak ne nastaje, samo WARN.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void zabiljezi(String rn, UUID submissionId, String facilityId, FacilityOwnershipRow facility) {
        if (facility == null || !Boolean.FALSE.equals(facility.getVerified())) {
            return;
        }
        Long documentId = facility.getDocumentId();
        if (documentId == null) {
            log.warn("eturizam_verifikacija_bez_dokumenta rn={} facility={} — neverificiran objekt nema "
                    + "document_id, zahtjev za žurnu verifikaciju se ne može upisati", rn, facilityId);
            return;
        }
        Instant now = clock.instant();
        // Prvi pokušaj je inline nakon commita; job redak smije uzeti tek nakon backoff-base, da ga ne preteče.
        repository.save(VerifikacijaOutboxEntity.pending(
                rn, submissionId, facilityId, documentId, now, policy.nextAttemptAt(now, 0)));
        log.info("eturizam_verifikacija_zabiljezeno rn={} facility={} document={}", rn, facilityId, documentId);
    }

    /**
     * Pokušaj nije uspio — zakazuje sljedeći, a nakon zadnjeg odustaje (ERROR). Ovo je jedini zapis
     * neuspjeha u logu (WARN sa stack traceom, ERROR pri odustajanju).
     *
     * <p>Broji se samo ako redak još ima {@code ocekivaniPokusaji} pokušaja: kad dvije instance (ili
     * zakašnjeli inline pokušaj i job) padnu na istom pokušaju, drugi neuspjeh se ne broji ponovno.
     *
     * @param ocekivaniPokusaji broj pokušaja koji je neuspjeli pokušaj vidio
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String rn, int ocekivaniPokusaji, Throwable cause) {
        String reason = describe(cause);
        VerifikacijaOutboxEntity e = repository.lockByRn(rn).orElse(null);
        if (e == null || !e.isPending() || e.getAttempts() != ocekivaniPokusaji) {
            log.info("eturizam_verifikacija_failed_ignored rn={} ocekivano={} — neuspjeh je već zabilježio drugi "
                    + "pokušaj ili je redak obrađen: {}", rn, ocekivaniPokusaji, reason);
            return;
        }
        Instant now = clock.instant();
        e.recordFailure(now, policy.nextAttemptAt(now, e.getAttempts()), reason);
        if (policy.isExhausted(e.getAttempts())) {
            e.abandon(now, "exhausted: " + reason);
            log.error("eturizam_verifikacija_exhausted rn={} document={} facility={} attempts={} reason={} — "
                            + "zahtjev za žurnu verifikaciju NIJE upisan u eTurizam, potrebna je ručna intervencija "
                            + "(povrat u red: DEPLOY-*.md)",
                    rn, e.getDocumentId(), e.getFacilityId(), e.getAttempts(), reason, cause);
        } else {
            log.warn("eturizam_verifikacija_failed rn={} document={} attempt={} next_attempt_at={} reason={} — "
                            + "RB ostaje valjan, pokušaj se ponavlja",
                    rn, e.getDocumentId(), e.getAttempts(), e.getNextAttemptAt(), reason, cause);
        }
        repository.save(e);
    }

    /** Najdublji uzrok je obično SQL poruka (npr. {@code permission denied}) — ona ide u {@code last_error}. */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<VerifikacijaOutboxEntity> due() {
        return repository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                VerifikacijaOutboxEntity.Status.PENDING, clock.instant(), PageRequest.of(0, retry.batchSize()));
    }
}
