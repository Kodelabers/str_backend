package com.str.backend.rb;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.domain.RbStatus;
import com.str.backend.domain.RbTrigger;
import com.str.backend.exception.IllegalStatusTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RbStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(RbStatusTransitionService.class);
    private static final String ENTITY_TYPE = "RB";

    private final AuditLogRepository auditLogRepository;

    public RbStatusTransitionService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void transition(RbEntity rb, RbStatus target, RbTrigger trigger) {
        RbStatus current = rb.getStatus();
        if (!current.canTransitionTo(target, trigger)) {
            throw new IllegalStatusTransitionException(
                    "Illegal rb transition: " + current + " -> " + target + " (trigger=" + trigger + ")");
        }
        rb.applyStatus(target);
        auditLogRepository.save(AuditLogEntity.transition(
                ENTITY_TYPE, rb.getRb(), current.name(), target.name(), trigger.name()));
        log.info("rb_transition rb={} from={} to={} trigger={}", rb.getRb(), current, target, trigger);
    }
}
