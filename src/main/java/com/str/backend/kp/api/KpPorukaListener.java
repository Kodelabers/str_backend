package com.str.backend.kp.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Nakon commita radnje šalje njene poruke izvan niti zahtjeva. */
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
            service.posaljiAsync(event.ids());
        } catch (RuntimeException e) {
            // Poruke su zabilježene; šalje ih KpApiRetryJob.
            log.error("kp_api_listener_failed poruka={} — šalje ih retry posao", event.ids().size(), e);
        }
    }
}
