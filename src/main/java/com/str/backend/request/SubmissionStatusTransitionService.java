package com.str.backend.request;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.domain.SubmissionStatus;
import com.str.backend.domain.SubmissionTrigger;
import com.str.backend.exception.IllegalStatusTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionStatusTransitionService.class);
    private static final String ENTITY_TYPE = "ZAHTJEV";

    private final AuditLogRepository auditLogRepository;

    public SubmissionStatusTransitionService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void transition(SubmissionEntity submission, SubmissionStatus target, SubmissionTrigger trigger) {
        SubmissionStatus current = submission.getStatus();
        if (!current.canTransitionTo(target, trigger)) {
            throw new IllegalStatusTransitionException(
                    "Illegal submission transition: " + current + " -> " + target + " (trigger=" + trigger + ")");
        }
        submission.applyStatus(target);
        auditLogRepository.save(AuditLogEntity.transition(
                ENTITY_TYPE, submission.getSubmissionId().toString(),
                current.name(), target.name(), trigger.name()));
        log.info("submission_transition id={} from={} to={} trigger={}",
                submission.getSubmissionId(), current, target, trigger);
    }
}
