package com.str.backend.kp;

import com.str.backend.kp.client.KpAktivnost;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Perzistencija otpreme u KP, svaki upis u vlastitoj kratkoj transakciji — isti razlog kao
 * {@code EgopFilingStore}: SOAP poziv ne smije stajati unutar transakcije.
 */
@Component
public class KpOtpremaStore {

    private final KpOtpremaRepository repository;

    public KpOtpremaStore(KpOtpremaRepository repository) {
        this.repository = repository;
    }

    /**
     * Zapisuje otpremu za pismeno ako je još nema. Paralelni upis (retry listenera, druga
     * instanca) puca na {@code uq_kp_otprema_pismeno} i tada vrijedi postojeći redak.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KpOtpremaEntity register(UUID pismenoId, KpPrimatelj primatelj, Instant aktNastaoAt) {
        Optional<KpOtpremaEntity> existing = repository.findByPismenoId(pismenoId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return repository.saveAndFlush(KpOtpremaEntity.pending(pismenoId, primatelj, aktNastaoAt));
        } catch (DataIntegrityViolationException e) {
            return repository.findByPismenoId(pismenoId).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<KpOtpremaEntity> find(UUID otpremaId) {
        return repository.findById(otpremaId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<KpOtpremaEntity> findByPismeno(UUID pismenoId) {
        return repository.findByPismenoId(pismenoId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID otpremaId, Instant sentAt, int tipPoruke, boolean pravniUcinak, String gsbId) {
        repository.findById(otpremaId).ifPresent(o -> {
            o.markSent(sentAt, tipPoruke, pravniUcinak, gsbId);
            repository.save(o);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSkipped(UUID otpremaId, String reason) {
        repository.findById(otpremaId).ifPresent(o -> {
            o.markSkipped(reason);
            repository.save(o);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markNemaPretinca(UUID otpremaId, String errorCode, String error, String gsbId) {
        repository.findById(otpremaId).ifPresent(o -> {
            o.markNemaPretinca(errorCode, error, gsbId);
            repository.save(o);
        });
    }

    /** @return broj pokušaja nakon ovog neuspjeha */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markFailed(UUID otpremaId, String errorCode, String error, Instant nextAttemptAt, String gsbId) {
        return repository.findById(otpremaId).map(o -> {
            o.markFailed(errorCode, error, nextAttemptAt, gsbId);
            repository.save(o);
            return o.getAttempts();
        }).orElse(0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExhausted(UUID otpremaId, String errorCode, String error, int maxAttempts, String gsbId) {
        repository.findById(otpremaId).ifPresent(o -> {
            o.markExhausted(errorCode, error, maxAttempts, gsbId);
            repository.save(o);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyAktivnost(UUID otpremaId, KpAktivnost aktivnost, Instant checkedAt) {
        repository.findById(otpremaId).ifPresent(o -> {
            o.applyAktivnost(aktivnost, checkedAt);
            repository.save(o);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markChecked(UUID otpremaId, Instant checkedAt) {
        repository.findById(otpremaId).ifPresent(o -> {
            o.markChecked(checkedAt);
            repository.save(o);
        });
    }
}
