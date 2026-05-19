package com.str.backend.request;

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

    private final SubmissionLogRepository submissionLogRepository;

    public SubmissionStatusTransitionService(SubmissionLogRepository submissionLogRepository) {
        this.submissionLogRepository = submissionLogRepository;
    }

    @Transactional
    public void transition(SubmissionEntity submission, SubmissionStatus target, SubmissionTrigger trigger) {
        transition(submission, target, trigger, null);
    }

    @Transactional
    public void transition(SubmissionEntity submission, SubmissionStatus target, SubmissionTrigger trigger,
                           String actor) {
        SubmissionStatus current = submission.getStatus();
        if (!current.canTransitionTo(target, trigger)) {
            throw new IllegalStatusTransitionException(
                    "Illegal submission transition: " + current + " -> " + target + " (trigger=" + trigger + ")");
        }
        submission.applyStatus(target);
        submissionLogRepository.save(SubmissionLogEntity.transition(
                submission.getSubmissionId(), current.name(), target.name(), trigger.name(), actor));
        log.info("submission_transition id={} from={} to={} trigger={}",
                submission.getSubmissionId(), current, target, trigger);
    }
}
