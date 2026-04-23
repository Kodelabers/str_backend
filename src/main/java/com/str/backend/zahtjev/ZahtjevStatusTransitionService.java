package com.str.backend.zahtjev;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.domain.ZahtjevStatus;
import com.str.backend.domain.ZahtjevTrigger;
import com.str.backend.exception.IllegalStatusTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZahtjevStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ZahtjevStatusTransitionService.class);
    private static final String ENTITY_TYPE = "ZAHTJEV";

    private final AuditLogRepository auditLogRepository;

    public ZahtjevStatusTransitionService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void transition(ZahtjevEntity zahtjev, ZahtjevStatus target, ZahtjevTrigger trigger) {
        ZahtjevStatus current = zahtjev.getStatus();
        if (!current.canTransitionTo(target, trigger)) {
            throw new IllegalStatusTransitionException(
                    "Illegal zahtjev transition: " + current + " -> " + target + " (trigger=" + trigger + ")");
        }
        zahtjev.applyStatus(target);
        auditLogRepository.save(AuditLogEntity.transition(
                ENTITY_TYPE, zahtjev.getIdZahtjeva().toString(),
                current.name(), target.name(), trigger.name()));
        log.info("zahtjev_transition id={} from={} to={} trigger={}",
                zahtjev.getIdZahtjeva(), current, target, trigger);
    }
}
