package com.str.backend.rn;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
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
    private static final String ENTITY_TYPE = "RN";

    private final AuditLogRepository auditLogRepository;

    public RnStatusTransitionService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void transition(RnEntity rn, RnStatus target, RnTrigger trigger) {
        RnStatus current = rn.getStatus();
        if (!current.canTransitionTo(target, trigger)) {
            throw new IllegalStatusTransitionException(
                    "Illegal rn transition: " + current + " -> " + target + " (trigger=" + trigger + ")");
        }
        rn.applyStatus(target);
        auditLogRepository.save(AuditLogEntity.transition(
                ENTITY_TYPE, rn.getRn(), current.name(), target.name(), trigger.name()));
        log.info("rn_transition rn={} from={} to={} trigger={}", rn.getRn(), current, target, trigger);
    }
}
