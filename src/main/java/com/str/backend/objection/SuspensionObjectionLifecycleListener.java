package com.str.backend.objection;

import com.str.backend.domain.RnStatus;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Zatvara neobrađeni prigovor kad RB napusti postupak na koji se prigovor odnosi drugim putem
 * (službenik ručno obustavi postupak ili reaktivira, opoziv, prijedlog povlačenja) — o prigovoru se
 * tada više nema što odlučiti.
 *
 * <p>Otvorenim ostaje samo uz istek roka istog postupka: prijedlog suspenzije → suspenzija i prijedlog
 * povlačenja → povlačenje. Službenik o takvom prigovoru i dalje odlučuje. Takav prijelaz nastaje samo uz
 * prigovor predan nakon roka — uz prigovor predan u roku noćni posao čeka službenika, a kad ga službenik
 * ne uvaži, prigovor je prije prijelaza već {@code REJECTED} ({@link SuspensionObjectionAdminService#reject}).
 *
 * <p>Ulazak u prijedlog povlačenja zatvara otvoren prigovor na prijedlog suspenzije ili na suspenziju
 * (odluka korisnika, T12): pokreće se novi postupak, a na njegov prijedlog stranka se očituje iznova.
 *
 * <p>Sinkroni {@link EventListener}, ne {@code AFTER_COMMIT}: radi u transakciji prijelaza, pa
 * prigovor i status RB-a ne mogu ostati u nesuglasju. Uvaženi prigovor je u tom trenutku već
 * {@code ACCEPTED} ({@link SuspensionObjectionAdminService#accept}), pa ga zatvaranje ne dira.
 */
@Component
public class SuspensionObjectionLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(SuspensionObjectionLifecycleListener.class);
    static final String SYSTEM_ACTOR = "SYSTEM";

    private final SuspensionObjectionRepository repository;

    public SuspensionObjectionLifecycleListener(SuspensionObjectionRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void onTransition(RnLifecycleEvent event) {
        if (ostajeOtvoren(event.from(), event.to())) {
            return;
        }
        List<SuspensionObjectionEntity> open =
                repository.findByRnAndStatus(event.rn(), SuspensionObjectionStatus.SUBMITTED);
        for (SuspensionObjectionEntity objection : open) {
            objection.close(SYSTEM_ACTOR);
            log.info("suspension_objection_closed rn={} objection={} to={}",
                    event.rn(), objection.getObjectionId(), event.to());
        }
    }

    /** Prijelaz kojim prijedlog istekom roka prelazi u mjeru — prigovor na prijedlog i dalje vrijedi. */
    static boolean ostajeOtvoren(RnStatus from, RnStatus to) {
        return (from == RnStatus.SUSPENSION_PROPOSED && to == RnStatus.SUSPENDED)
                || (from == RnStatus.WITHDRAWAL_PROPOSED && to == RnStatus.WITHDRAWN);
    }
}
