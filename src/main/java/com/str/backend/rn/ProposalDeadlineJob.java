package com.str.backend.rn;

import com.str.backend.common.Zagreb;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.objection.SuspensionObjectionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Zajednička osnova noćnih poslova dvofaznog postupka ({@link SuspensionDeadlineJob},
 * {@link WithdrawalDeadlineJob}): RB-ovi čiji je rok za očitovanje istekao prelaze u mjeru.
 *
 * <p>Svaki RB se ponovno čita zaključan: iznajmljivač ga je u međuvremenu mogao opozvati, a službenik
 * obustaviti postupak. Bez toga bi posao prepisao novo stanje mjerom iz zastarjelog.
 *
 * <p>Iznimka je RB uz koji čeka prigovor (očitovanje, {@code SUBMITTED}) na <b>trenutni</b> prijedlog,
 * predan do kraja dana roka: o mjeri tada prvo odlučuje službenik. Prigovor predan nakon roka mjeru ne
 * zaustavlja, a ni prigovor na neki raniji prijedlog istog RB-a.
 *
 * <p>Sve po hrvatskom vremenu (kontejner je UTC): „danas", kraj dana roka i sam cron.
 */
abstract class ProposalDeadlineJob {


    /** Logger podrazreda — imena logera ostaju kakva su bila prije zajedničke osnove. */
    private final Logger log = LoggerFactory.getLogger(getClass());
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final ProposalProcedure procedure;
    private final RnRepository rnRepository;
    private final RnStatusTransitionService transitionService;
    private final SuspensionObjectionRepository objectionRepository;
    private final RegistrationNumberLogRepository logRepository;

    ProposalDeadlineJob(ProposalProcedure procedure, RnRepository rnRepository,
                        RnStatusTransitionService transitionService,
                        SuspensionObjectionRepository objectionRepository,
                        RegistrationNumberLogRepository logRepository) {
        this.procedure = procedure;
        this.rnRepository = rnRepository;
        this.transitionService = transitionService;
        this.objectionRepository = objectionRepository;
        this.logRepository = logRepository;
    }

    /** RB-ovi u prijedlogu ovog postupka kojima je rok strogo prije {@code today}. */
    protected abstract List<String> findExpired(RnRepository repository, LocalDate today);

    /** Tijelo noćnog posla; podrazred ga zove iz {@code @Scheduled @Transactional run()}. */
    protected void expireDeadlines() {
        LocalDate today = Zagreb.today();
        List<String> expired = findExpired(rnRepository, today);
        if (expired.isEmpty()) return;
        String key = procedure.logKey();
        log.info("{}_job processing={}", key, expired.size());
        for (String id : expired) {
            RnEntity rn = rnRepository.findForUpdate(id).orElse(null);
            if (rn == null || !procedure.isExpired(rn, today)) {
                log.info("{}_skipped rn={} status={}", key, id, rn == null ? null : rn.getStatus());
                continue;
            }
            LocalDate deadline = procedure.deadline(rn);
            if (imaPrigovorURoku(id, deadline)) {
                log.info("{}_skipped_objection rn={} deadline={}", key, id, deadline);
                continue;
            }
            transitionService.transition(rn, procedure.target(), procedure.trigger(), SYSTEM_ACTOR, procedure.note());
            log.info("{}_exceeded rn={} deadline={}", key, id, deadline);
        }
    }

    /**
     * Prigovor na trenutni prijedlog (zadnji prijelaz u status prijedloga), predan do kraja dana roka.
     * Bez zapisa prijedloga (stariji podaci) vrijedi svaki neobrađen prigovor u roku.
     */
    private boolean imaPrigovorURoku(String rn, LocalDate deadline) {
        return logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(rn, procedure.proposed().name())
                .map(prijedlog -> objectionRepository.existsSubmittedForProposal(rn, prijedlog.getLogId(),
                        prijedlog.getOccurredAt(), endOfDay(deadline)))
                .orElseGet(() -> objectionRepository.existsByRnAndStatusAndSubmittedAtBefore(rn,
                        SuspensionObjectionStatus.SUBMITTED, endOfDay(deadline)));
    }

    /** Prvi trenutak nakon dana roka — prigovor predan prije njega je predan u roku. */
    static Instant endOfDay(LocalDate deadline) {
        return deadline.plusDays(1).atStartOfDay(Zagreb.ZONE).toInstant();
    }
}
