package com.str.backend.registration.event;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.event.RnLifecycleEvent;
import com.str.backend.str.FacilityRegistrationNumberWriteBack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Povučeni RB briše se iz eTurizma; ostali prijelazi (suspenzija, reaktivacija) ga ne diraju. */
class RnWithdrawnListenerTest {

    private static final String RN = "HR120001000000000123";

    private final FacilityRegistrationNumberWriteBack writeBack = mock(FacilityRegistrationNumberWriteBack.class);
    private final RnWithdrawnListener listener = new RnWithdrawnListener(writeBack);

    private static RnLifecycleEvent event(RnStatus from, RnStatus to, RnTrigger trigger) {
        return new RnLifecycleEvent(UUID.randomUUID(), RN, from, to, trigger, "NIAS:12312312316", null);
    }

    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"ACTIVE", "SUSPENSION_PROPOSED", "SUSPENDED"})
    void withdrawal_fromAnyStandingStatus_clearsRnInEturizam(RnStatus from) {
        listener.onLifecycleChange(event(from, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL));

        verify(writeBack).clear(RN);
    }

    /** Suspendiran broj i dalje je broj objekta — novi se ne smije tražiti. */
    @Test
    void suspension_keepsRnInEturizam() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnTrigger.INSPECTION));

        verifyNoInteractions(writeBack);
    }
}
