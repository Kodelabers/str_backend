package com.str.backend.kp.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Nakon commita radnje šalje njene poruke sesijom službenika, izvan niti zahtjeva. */
@Component
class KpPorukaListener {

    private static final Logger log = LoggerFactory.getLogger(KpPorukaListener.class);

    private final KpPorukaService service;

    KpPorukaListener(KpPorukaService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onZabiljezene(KpPorukeZabiljezene event) {
        try {
            service.posaljiAsync(event.ids(), event.sessionId());
        } catch (RuntimeException e) {
            // Poruke su zabilježene; pošalje ih sljedeći zahtjev službenika iste županije.
            log.error("kp_api_listener_failed poruka={} — šalju se pri sljedećem zahtjevu", event.ids().size(), e);
        }
    }
}
