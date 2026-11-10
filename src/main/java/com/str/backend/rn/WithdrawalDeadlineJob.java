package com.str.backend.rn;

import com.str.backend.objection.SuspensionObjectionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * T12: svaki dan provjerava RB-ove u statusu WITHDRAWAL_PROPOSED čiji je rok za očitovanje istekao
 * i povlači ih (WITHDRAWN, okidač WITHDRAWAL_DEADLINE_EXCEEDED). Ručnog povlačenja nema: osim ovog
 * posla broj povlači samo službenik koji nakon roka ne uvaži očitovanje
 * ({@code RnService#withdrawIfDeadlineExpired}).
 *
 * <p>Preskače RB uz očitovanje na trenutni prijedlog povlačenja predano do kraja dana roka. Prigovori
 * iz prethodnog postupka suspenzije ovdje ne smetaju: ulazak u prijedlog povlačenja ih zatvara
 * ({@code SuspensionObjectionLifecycleListener}). Akt o povlačenju ispisuje razlog i potpisnika s
 * prijedloga povlačenja, a ne bilješku posla ({@code StrDocumentService}).
 *
 * <p>Tijek dijeli sa suspenzijom ({@link ProposalDeadlineJob}); sve po hrvatskom vremenu.
 */
@Component
public class WithdrawalDeadlineJob extends ProposalDeadlineJob {

    /** Bilješka uz prijelaz istekom roka — ista za posao i za povlačenje nakon neuvažena prigovora. */
    static final String DEADLINE_EXCEEDED_NOTE = ProposalProcedure.WITHDRAWAL.note();

    public WithdrawalDeadlineJob(RnRepository rnRepository,
                                 RnStatusTransitionService transitionService,
                                 SuspensionObjectionRepository objectionRepository,
                                 RegistrationNumberLogRepository logRepository) {
        super(ProposalProcedure.WITHDRAWAL, rnRepository, transitionService, objectionRepository, logRepository);
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Europe/Zagreb")
    @Transactional
    public void run() {
        expireDeadlines();
    }

    @Override
    protected List<String> findExpired(RnRepository repository, LocalDate today) {
        return repository.findRnsWithWithdrawalDeadlineBefore(today);
    }
}
