package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Čitanja RB-a za slušatelje životnog ciklusa, svako u <b>vlastitoj kratkoj transakciji</b>.
 *
 * <p>Postoji zato da slušatelji ne moraju biti transakcijski. Oni rade nakon commita i zovu
 * spore vanjske sustave — urudžbiranje do ~7 SOAP poziva, dostavu jedan SMTP — pa bi im
 * {@code @Transactional} na metodi držao konekciju otvorenu kroz cijelo to čekanje. Isti
 * razlog stoji iza {@code EgopFilingStore}.
 *
 * <p>{@code REQUIRES_NEW} je nužan i sam po sebi: u {@code AFTER_COMMIT} fazi izvorna
 * transakcija je dovršena ali su joj sinkronizacije još aktivne, pa bi zadani {@code REQUIRED}
 * pokušao nastaviti nju.
 */
@Component
public class RnLifecycleLookup {

    private final RnRepository rnRepository;
    private final RegistrationNumberLogRepository logRepository;

    public RnLifecycleLookup(RnRepository rnRepository, RegistrationNumberLogRepository logRepository) {
        this.rnRepository = rnRepository;
        this.logRepository = logRepository;
    }

    /**
     * Događaj prijelaza rekonstruiran iz revizijskog zapisa — za ponovno slanje obavijesti, kad
     * izvornog eventa više nema. Zapis nosi sve što event nosi, pa je rekonstrukcija potpuna.
     *
     * @return prazno ako zapisa nema ili mu se status/okidač više ne mogu pročitati (preimenovan
     *         enum u starim podacima)
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<RnLifecycleEvent> findEvent(UUID logId) {
        return logRepository.findById(logId).flatMap(RnLifecycleLookup::toEvent);
    }

    /**
     * Je li prijelaz još zadnji za svoj RB. Obavijest o prijelazu koji je u međuvremenu nadjačan
     * (npr. prijedlog suspenzije pa obustava) ne smije stići nakon novije — stranka bi dobila
     * stanje koje više ne vrijedi.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isLatestTransition(String rn, UUID logId) {
        return logRepository.findFirstByRnOrderByOccurredAtDesc(rn)
                .map(zadnji -> zadnji.getLogId().equals(logId))
                .orElse(false);
    }

    private static Optional<RnLifecycleEvent> toEvent(RegistrationNumberLogEntity zapis) {
        try {
            return Optional.of(new RnLifecycleEvent(
                    zapis.getLogId(),
                    zapis.getRn(),
                    zapis.getFromStatus() == null ? null : RnStatus.valueOf(zapis.getFromStatus()),
                    RnStatus.valueOf(zapis.getToStatus()),
                    RnTrigger.valueOf(zapis.getTriggerName()),
                    zapis.getActor(),
                    zapis.getReason()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Predmet u kojem akt treba završiti vezan je uz submission RB-a. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> findSubmissionId(String rn) {
        return rnRepository.findById(rn).map(RnEntity::getSubmissionId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<RnDetailDto> findDetail(String rn) {
        return rnRepository.findDetail(rn);
    }
}
