package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.objection.SuspensionObjectionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * <p>Sve po hrvatskom vremenu (kontejner je UTC): „danas", kraj dana roka i sam cron.
 */
@Component
public class SuspensionDeadlineJob {

    private static final Logger log = LoggerFactory.getLogger(SuspensionDeadlineJob.class);
    /** Rok je datum po hrvatskom vremenu, neovisno o zoni poslužitelja (kontejner je UTC). */
    static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");
    /** Bilješka uz prijelaz istekom roka — ista za job i za suspenziju nakon neuvažena prigovora. */
    static final String DEADLINE_EXCEEDED_NOTE = "Rok za rješavanje nepravilnosti istekao.";

    private final RnRepository rnRepository;
    private final RnStatusTransitionService transitionService;
    private final SuspensionObjectionRepository objectionRepository;
    private final RegistrationNumberLogRepository logRepository;

    public SuspensionDeadlineJob(RnRepository rnRepository,
                                 RnStatusTransitionService transitionService,
                                 SuspensionObjectionRepository objectionRepository,
                                 RegistrationNumberLogRepository logRepository) {
        this.rnRepository = rnRepository;
        this.transitionService = transitionService;
        this.objectionRepository = objectionRepository;
        this.logRepository = logRepository;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Europe/Zagreb")
    @Transactional
    public void run() {
        LocalDate today = LocalDate.now(ZAGREB);
        List<String> expired = rnRepository.findRnsWithSuspensionDeadlineBefore(today);
        if (expired.isEmpty()) return;
        log.info("suspension_deadline_job processing={}", expired.size());
        for (String id : expired) {
            // Svaki RB se ponovo čita zaključan: iznajmljivač ga je u međuvremenu mogao opozvati,
            // a službenik povući ili obustaviti postupak. Bez toga bi job prepisao REVOKED/WITHDRAWN
            // suspenzijom iz zastarjelog stanja.
            RnEntity rn = rnRepository.findForUpdate(id).orElse(null);
            if (rn == null || rn.getStatus() != RnStatus.SUSPENSION_PROPOSED
                    || rn.getSuspensionDeadline() == null || !rn.getSuspensionDeadline().isBefore(today)) {
                log.info("suspension_deadline_skipped rn={} status={}", id,
                        rn == null ? null : rn.getStatus());
                continue;
            }
            LocalDate deadline = rn.getSuspensionDeadline();
            if (imaPrigovorURoku(id, deadline)) {
                log.info("suspension_deadline_skipped_objection rn={} deadline={}", id, deadline);
                continue;
            }
            transitionService.transition(rn, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
                    "SYSTEM", DEADLINE_EXCEEDED_NOTE);
            log.info("suspension_deadline_exceeded rn={} deadline={}", id, deadline);
        }
    }

    /**
     * Prigovor na trenutni prijedlog (zadnji prijelaz u {@code SUSPENSION_PROPOSED}), predan do kraja
     * dana roka. Bez zapisa prijedloga (stariji podaci) vrijedi svaki neobrađen prigovor u roku.
     */
    private boolean imaPrigovorURoku(String rn, LocalDate deadline) {
        return logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(rn, RnStatus.SUSPENSION_PROPOSED.name())
                .map(prijedlog -> objectionRepository.existsSubmittedForProposal(rn, prijedlog.getLogId(),
                        prijedlog.getOccurredAt(), endOfDay(deadline)))
                .orElseGet(() -> objectionRepository.existsByRnAndStatusAndSubmittedAtBefore(rn,
                        SuspensionObjectionStatus.SUBMITTED, endOfDay(deadline)));
    }

    /** Prvi trenutak nakon dana roka — prigovor predan prije njega je predan u roku. */
    static Instant endOfDay(LocalDate deadline) {
        return deadline.plusDays(1).atStartOfDay(ZAGREB).toInstant();
    }
}
