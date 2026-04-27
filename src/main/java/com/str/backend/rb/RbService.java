package com.str.backend.rb;

import com.str.backend.domain.RbStatus;
import com.str.backend.domain.RbTrigger;
import com.str.backend.domain.RegistracijskiBroj;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.VrstaSsoRepository;
import com.str.backend.sso.SsoEntity;
import com.str.backend.sso.SsoRepository;
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
    private final SsoRepository ssoRepository;
    private final VrstaSsoRepository vrstaSsoRepository;
    private final Clock clock;

    public RbService(RbRepository repository, RbStatusTransitionService transitionService,
                     SsoRepository ssoRepository, VrstaSsoRepository vrstaSsoRepository, Clock clock) {
        this.repository = repository;
        this.transitionService = transitionService;
        this.ssoRepository = ssoRepository;
        this.vrstaSsoRepository = vrstaSsoRepository;
        this.clock = clock;
    }

    @Transactional
    public RbEntity issue(UUID idZahtjeva, UUID idSso) {
        // STR spec §2.1: Hotel/kamp (rb_dozvoljen = false) must not receive RB.
        SsoEntity sso = ssoRepository.findById(idSso)
                .orElseThrow(() -> new ResourceNotFoundException("sso not found: " + idSso));
        if (sso.getIdVrsteSso() != null) {
            vrstaSsoRepository.findById(sso.getIdVrsteSso()).ifPresent(vrsta -> {
                if (!vrsta.isRbDozvoljen()) {
                    throw new BusinessException("rb cannot be issued for vrsta_sso=" + vrsta.getNaziv());
                }
            });
        }
        LocalDate danas = LocalDate.now(clock);
        for (int i = 0; i < MAX_RB_ATTEMPTS; i++) {
            String candidate = RegistracijskiBroj.generate().getValue();
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

    /** STR-1.5: lista nevažećih RB (SUSPENDIRAN + POVUCEN), za prikaz nadležnom tijelu. */
    @Transactional(readOnly = true)
    public List<RbEntity> nevazeci() {
        return repository.findByStatusInOrderByUpdatedAtDesc(List.of(RbStatus.SUSPENDIRAN, RbStatus.POVUCEN));
    }

    private RbEntity load(String rb) {
        return repository.findById(rb)
                .orElseThrow(() -> new ResourceNotFoundException("rb not found: " + rb));
    }
}
