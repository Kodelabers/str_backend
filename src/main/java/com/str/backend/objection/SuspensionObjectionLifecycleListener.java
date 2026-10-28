package com.str.backend.objection;

import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Zatvara neobrađeni prigovor kad RB napusti prijedlog suspenzije i suspenziju drugim putem
 * (službenik ručno povuče prijedlog ili reaktivira, opoziv, povlačenje) — o prigovoru se tada
 * više nema što odlučiti. Prijelaz prijedlog → suspenzija po isteku roka prigovor ostavlja
 * otvorenim: prigovor ne zaustavlja rok, a službenik o njemu i dalje odlučuje.
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
        if (SuspensionObjectionService.OBJECTION_ALLOWED_RN_STATUSES.contains(event.to())) {
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
}
