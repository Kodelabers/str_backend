package com.str.backend.registration.event;

import com.str.backend.rn.event.RnLifecycleEvent;
import com.str.backend.str.FacilityRegistrationNumberWriteBack;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Nakon opoziva ili povlačenja RB-a (i commita) briše ga iz eTurizam registra, da objekt može
 * dobiti novi broj i da eTurizam ne prikazuje konačni broj kao važeći. Sluša
 * {@link RnLifecycleEvent}, koji objavljuje jedini prolaz kroz koji status smije proći, pa pokriva
 * i opoziv iznajmljivača ({@code REVOKED}) i povlačenje po službenoj dužnosti ({@code WITHDRAWN}).
 */
@Component
public class RnWithdrawnListener {

    private final FacilityRegistrationNumberWriteBack facilityWriteBack;

    public RnWithdrawnListener(FacilityRegistrationNumberWriteBack facilityWriteBack) {
        this.facilityWriteBack = facilityWriteBack;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLifecycleChange(RnLifecycleEvent event) {
        if (event.to().isTerminal()) {
            facilityWriteBack.clear(event.rn());
        }
    }
}
