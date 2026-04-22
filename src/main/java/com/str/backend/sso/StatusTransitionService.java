package com.str.backend.sso;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.domain.Status;
import com.str.backend.domain.TransitionTrigger;
import com.str.backend.exception.IllegalStatusTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(StatusTransitionService.class);

    private final AuditLogRepository auditLogRepository;

    public StatusTransitionService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void transition(SsoEntity sso, Status target, TransitionTrigger trigger) {
        Status current = sso.getStatus();
        if (!current.canTransitionTo(target, trigger)) {
            throw new IllegalStatusTransitionException(current, target, trigger);
        }
        sso.applyStatus(target);
        auditLogRepository.save(AuditLogEntity.transition(
                sso.getUuidSso(), current.name(), target.name(), trigger.name()));
        log.info("status_transition uuidSso={} from={} to={} trigger={}",
                sso.getUuidSso(), current, target, trigger);
    }
}
