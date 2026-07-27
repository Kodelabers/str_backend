package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Svaki dan provjerava RB-ove u statusu SUSPENSION_PROPOSED čiji je rok istekao
 * i automatski ih prebacuje u SUSPENDED (trigger: DEADLINE_EXCEEDED).
 */
@Component
public class SuspensionDeadlineJob {

    private static final Logger log = LoggerFactory.getLogger(SuspensionDeadlineJob.class);

    private final RnRepository rnRepository;
    private final RnStatusTransitionService transitionService;

    public SuspensionDeadlineJob(RnRepository rnRepository,
                                 RnStatusTransitionService transitionService) {
        this.rnRepository = rnRepository;
        this.transitionService = transitionService;
    }

    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void run() {
        LocalDate today = LocalDate.now();
        List<RnEntity> expired = rnRepository.findByStatusAndSuspensionDeadlineBefore(
                RnStatus.SUSPENSION_PROPOSED, today);
        if (expired.isEmpty()) return;
        log.info("suspension_deadline_job processing={}", expired.size());
        for (RnEntity rn : expired) {
            transitionService.transition(rn, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
                    "SYSTEM", "Rok za rješavanje nepravilnosti istekao.");
            log.info("suspension_deadline_exceeded rn={} deadline={}", rn.getRn(), rn.getSuspensionDeadline());
        }
    }
}
