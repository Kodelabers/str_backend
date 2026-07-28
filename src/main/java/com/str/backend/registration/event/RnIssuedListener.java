package com.str.backend.registration.event;

import com.str.backend.egop.EgopRegistrationDispatcher;
import com.str.backend.str.FacilityRegistrationNumberWriteBack;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Nakon izdavanja RN-a (i commita registracijske transakcije) pokreće eGOP
 * urudžbiranje + non-EU dostavu preko {@link EgopRegistrationDispatcher}, te upis
 * RB-a natrag u eTurizam preko {@link FacilityRegistrationNumberWriteBack}.
 *
 * <p>Radi sinkrono nakon commita (isti thread) — PDF zahtjeva mora biti pohranjen
 * do povratka HTTP odgovora jer ga registracijski tok odmah nudi na dohvat. Ako
 * urudžbiranje padne, dispatcher ostavlja {@code egop_sync_status=FAILED}, a
 * {@link com.str.backend.egop.EgopRetryJob} ga naknadno pokupi — RN je valjan
 * neovisno o ishodu dostave.
 *
 * <p>Write-back ide <em>prvi</em> i sam guta svoje greške: kraći je i neovisan o
 * eGOP-u, pa nema razloga da ga eventualni pad urudžbiranja preskoči.
 */
@Component
public class RnIssuedListener {

    private final EgopRegistrationDispatcher dispatcher;
    private final FacilityRegistrationNumberWriteBack facilityWriteBack;

    public RnIssuedListener(EgopRegistrationDispatcher dispatcher,
                            FacilityRegistrationNumberWriteBack facilityWriteBack) {
        this.dispatcher = dispatcher;
        this.facilityWriteBack = facilityWriteBack;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRnIssued(RnIssuedEvent event) {
        facilityWriteBack.writeBack(event.submissionId(), event.rn());
        dispatcher.dispatch(event.submissionId());
    }
}
