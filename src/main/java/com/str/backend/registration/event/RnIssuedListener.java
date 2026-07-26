package com.str.backend.registration.event;

import com.str.backend.egop.EgopRegistrationDispatcher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Nakon izdavanja RN-a (i commita registracijske transakcije) pokreće eGOP
 * urudžbiranje + non-EU dostavu preko {@link EgopRegistrationDispatcher}.
 *
 * <p>Radi sinkrono nakon commita (isti thread) — PDF zahtjeva mora biti pohranjen
 * do povratka HTTP odgovora jer ga registracijski tok odmah nudi na dohvat. Ako
 * urudžbiranje padne, dispatcher ostavlja {@code egop_sync_status=FAILED}, a
 * {@link com.str.backend.egop.EgopRetryJob} ga naknadno pokupi — RN je valjan
 * neovisno o ishodu dostave.
 */
@Component
public class RnIssuedListener {

    private final EgopRegistrationDispatcher dispatcher;

    public RnIssuedListener(EgopRegistrationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRnIssued(RnIssuedEvent event) {
        dispatcher.dispatch(event.submissionId());
    }
}
