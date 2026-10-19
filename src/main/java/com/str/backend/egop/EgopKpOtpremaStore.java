package com.str.backend.egop;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Perzistencija otpreme u KP, svaki upis u vlastitoj kratkoj transakciji — isti razlog kao
 * {@link EgopFilingStore}: SOAP poziv ne smije stajati unutar transakcije.
 */
@Component
public class EgopKpOtpremaStore {

    private final EgopKpOtpremaRepository repository;

    public EgopKpOtpremaStore(EgopKpOtpremaRepository repository) {
        this.repository = repository;
    }

    /**
     * Zapisuje otpremu za pismeno ako je još nema. Paralelni upis (retry listenera, druga
     * instanca) puca na {@code uq_egop_kp_otprema_pismeno} i tada vrijedi postojeći redak.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EgopKpOtpremaEntity register(UUID pismenoId, String primateljOib, Instant aktNastaoAt) {
        Optional<EgopKpOtpremaEntity> existing = repository.findByPismenoId(pismenoId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return repository.saveAndFlush(EgopKpOtpremaEntity.pending(pismenoId, primateljOib, aktNastaoAt));
        } catch (DataIntegrityViolationException e) {
            return repository.findByPismenoId(pismenoId).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<EgopKpOtpremaEntity> find(UUID otpremaId) {
        return repository.findById(otpremaId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<EgopKpOtpremaEntity> findByPismeno(UUID pismenoId) {
        return repository.findByPismenoId(pismenoId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID otpremaId, Instant sentAt) {
        repository.findById(otpremaId).ifPresent(o -> {
            o.markSent(sentAt);
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

    /** @return broj pokušaja nakon ovog neuspjeha */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markFailed(UUID otpremaId, String error, Instant nextAttemptAt) {
        return repository.findById(otpremaId).map(o -> {
            o.markFailed(error, nextAttemptAt);
            repository.save(o);
            return o.getAttempts();
        }).orElse(0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyKpStatus(UUID otpremaId, KpStatusPoruke kp, Instant checkedAt) {
        repository.findById(otpremaId).ifPresent(o -> {
            o.applyKpStatus(kp, checkedAt);
            repository.save(o);
        });
    }
}
