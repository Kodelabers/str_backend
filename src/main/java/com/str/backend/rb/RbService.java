package com.str.backend.rb;

import com.str.backend.domain.RbStatus;
import com.str.backend.domain.RbTrigger;
import com.str.backend.domain.RegistracijskiBroj;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class RbService {

    private static final Logger log = LoggerFactory.getLogger(RbService.class);
    private static final int MAX_RB_ATTEMPTS = 5;

    private final RbRepository repository;
    private final RbStatusTransitionService transitionService;
    private final Clock clock;

    public RbService(RbRepository repository, RbStatusTransitionService transitionService, Clock clock) {
        this.repository = repository;
        this.transitionService = transitionService;
        this.clock = clock;
    }

    @Transactional
    public RbEntity issue(UUID idZahtjeva, UUID idSso) {
        LocalDate danas = LocalDate.now(clock);
        for (int i = 0; i < MAX_RB_ATTEMPTS; i++) {
            String candidate = RegistracijskiBroj.generate().value();
            if (!repository.existsByRb(candidate)) {
                RbEntity rb = RbEntity.issue(candidate, idZahtjeva, idSso, danas);
                repository.save(rb);
                log.info("rb_issued rb={} zahtjev={} sso={}", candidate, idZahtjeva, idSso);
                return rb;
            }
        }
        throw new BusinessException("cannot generate unique registracijski broj after "
                + MAX_RB_ATTEMPTS + " attempts");
    }

    @Transactional
    public RbEntity suspend(String rb, RbTrigger trigger) {
        if (trigger != RbTrigger.CONSENT_EXPIRY && trigger != RbTrigger.INSPECTION) {
            throw new BusinessException("suspend requires CONSENT_EXPIRY or INSPECTION trigger");
        }
        RbEntity e = load(rb);
        transitionService.transition(e, RbStatus.SUSPENDIRAN, trigger);
        return e;
    }

    @Transactional
    public RbEntity reactivate(String rb) {
        RbEntity e = load(rb);
        transitionService.transition(e, RbStatus.AKTIVAN, RbTrigger.REACTIVATE);
        return e;
    }

    @Transactional
    public RbEntity withdraw(String rb) {
        RbEntity e = load(rb);
        transitionService.transition(e, RbStatus.POVUCEN, RbTrigger.WITHDRAWAL);
        return e;
    }

    @Transactional(readOnly = true)
    public RbEntity dohvati(String rb) {
        return load(rb);
    }

    @Transactional(readOnly = true)
    public List<RbEntity> zaSso(UUID idSso) {
        return repository.findByIdSso(idSso);
    }

    private RbEntity load(String rb) {
        return repository.findById(rb)
                .orElseThrow(() -> new ResourceNotFoundException("rb not found: " + rb));
    }
}
