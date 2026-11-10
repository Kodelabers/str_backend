package com.str.backend.rn;

import com.str.backend.objection.SuspensionObjectionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Svaki dan provjerava RB-ove u statusu SUSPENSION_PROPOSED čiji je rok istekao
 * i automatski ih prebacuje u SUSPENDED (trigger: DEADLINE_EXCEEDED).
 *
 * <p>Iznimka je RB uz koji čeka prigovor ({@code SUBMITTED}) predan do kraja dana roka: stranka
 * se u roku očitovala, pa o suspenziji prvo odlučuje službenik — uvažavanje obustavlja postupak,
 * a neuvažavanje odmah suspendira ({@code RnService#suspendIfDeadlineExpired}). Prigovor predan
 * nakon roka suspenziju ne zaustavlja, a ni prigovor na neki raniji prijedlog istog RB-a.
 *
 * <p>Tijek dijeli s povlačenjem ({@link ProposalDeadlineJob}); sve po hrvatskom vremenu.
 */
@Component
public class SuspensionDeadlineJob extends ProposalDeadlineJob {

    /** Bilješka uz prijelaz istekom roka — ista za job i za suspenziju nakon neuvažena prigovora. */
    static final String DEADLINE_EXCEEDED_NOTE = ProposalProcedure.SUSPENSION.note();

    public SuspensionDeadlineJob(RnRepository rnRepository,
                                 RnStatusTransitionService transitionService,
                                 SuspensionObjectionRepository objectionRepository,
                                 RegistrationNumberLogRepository logRepository) {
        super(ProposalProcedure.SUSPENSION, rnRepository, transitionService, objectionRepository, logRepository);
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Europe/Zagreb")
    @Transactional
    public void run() {
        expireDeadlines();
    }

    @Override
    protected List<String> findExpired(RnRepository repository, LocalDate today) {
        return repository.findRnsWithSuspensionDeadlineBefore(today);
    }
}
