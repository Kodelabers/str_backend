package com.str.backend.rn;

import com.str.backend.rn.dto.RnDetailDto;
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

    public RnLifecycleLookup(RnRepository rnRepository) {
        this.rnRepository = rnRepository;
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
