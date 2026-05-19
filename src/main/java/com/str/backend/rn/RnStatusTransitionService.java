package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.IllegalStatusTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RnStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(RnStatusTransitionService.class);

    private final RegistrationNumberLogRepository rnLogRepository;

    public RnStatusTransitionService(RegistrationNumberLogRepository rnLogRepository) {
        this.rnLogRepository = rnLogRepository;
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
        rnLogRepository.save(RegistrationNumberLogEntity.transition(
                rn.getRn(), current.name(), target.name(), trigger.name(), actor, reason));
        log.info("rn_transition rn={} from={} to={} trigger={}", rn.getRn(), current, target, trigger);
    }
}
