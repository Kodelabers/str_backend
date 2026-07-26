package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.IllegalStatusTransitionException;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RnStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(RnStatusTransitionService.class);

    private final RegistrationNumberLogRepository rnLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RnStatusTransitionService(RegistrationNumberLogRepository rnLogRepository,
                                     ApplicationEventPublisher eventPublisher) {
        this.rnLogRepository = rnLogRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void transition(RnEntity rn, RnStatus target, RnTrigger trigger) {
        transition(rn, target, trigger, null, null);
    }

    @Transactional
    public void transition(RnEntity rn, RnStatus target, RnTrigger trigger, String actor, String reason) {
        RnStatus current = rn.getStatus();
        if (!current.canTransitionTo(target, trigger)) {
            throw new IllegalStatusTransitionException(
                    "Illegal rn transition: " + current + " -> " + target + " (trigger=" + trigger + ")");
        }
        rn.applyStatus(target);
        // logId dodjeljuje factory, pa ga uzimamo s vlastite reference umjesto iz povratne
        // vrijednosti save() — identitet zapisa ne smije ovisiti o tome što repozitorij vrati.
        RegistrationNumberLogEntity zapis = RegistrationNumberLogEntity.transition(
                rn.getRn(), current.name(), target.name(), trigger.name(), actor, reason);
        rnLogRepository.save(zapis);
        log.info("rn_transition rn={} from={} to={} trigger={}", rn.getRn(), current, target, trigger);
        // Objava je ovdje, a ne u RnService, jer je ovo jedini prolaz kroz koji status smije
        // proći — obavijest time ne može promaknuti ni jednom budućem pozivatelju. Slušatelji
        // rade AFTER_COMMIT, pa vide i podatke koje pozivatelj postavi nakon ovog poziva
        // (npr. suspensionDeadline u RnService#suspend).
        eventPublisher.publishEvent(new RnLifecycleEvent(
                zapis.getLogId(), rn.getRn(), current, target, trigger, actor, reason));
    }
}
