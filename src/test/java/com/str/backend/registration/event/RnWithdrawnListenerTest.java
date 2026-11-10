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

/** Opozvani i povučeni RB briše se iz eTurizma; ostali prijelazi (suspenzija, reaktivacija) ga ne diraju. */
class RnWithdrawnListenerTest {

    private static final String RN = "HR120001000000000123";

    private final FacilityRegistrationNumberWriteBack writeBack = mock(FacilityRegistrationNumberWriteBack.class);
    private final RnWithdrawnListener listener = new RnWithdrawnListener(writeBack);

    private static RnLifecycleEvent event(RnStatus from, RnStatus to, RnTrigger trigger) {
        return new RnLifecycleEvent(UUID.randomUUID(), RN, from, to, trigger, "NIAS:12312312316", null);
    }

    /** Od T12 povlačenje ide samo iz prijedloga — odlukom službenika ili istekom roka. */
    @ParameterizedTest
    @EnumSource(value = RnTrigger.class, names = {"WITHDRAWAL", "WITHDRAWAL_DEADLINE_EXCEEDED"})
    void withdrawal_fromProposal_clearsRnInEturizam(RnTrigger trigger) {
        listener.onLifecycleChange(event(RnStatus.WITHDRAWAL_PROPOSED, RnStatus.WITHDRAWN, trigger));

        verify(writeBack).clear(RN);
    }

    /** Uvaženi prigovor na povlačenje: broj je opet broj objekta i vraća se u eTurizam. */
    @Test
    void reinstate_restoresRnInEturizam() {
        listener.onLifecycleChange(event(RnStatus.WITHDRAWN, RnStatus.ACTIVE, RnTrigger.REINSTATE));

        verify(writeBack).restore(RN);
        verify(writeBack, org.mockito.Mockito.never()).clear(RN);
    }

    /** Prijedlog povlačenja i njegova obustava broj ostavljaju u eTurizmu. */
    @Test
    void withdrawalProposalAndRevocation_keepRnInEturizam() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.WITHDRAWAL_PROPOSED, RnTrigger.WITHDRAWAL_PROPOSAL));
        listener.onLifecycleChange(event(RnStatus.WITHDRAWAL_PROPOSED, RnStatus.ACTIVE,
                RnTrigger.REVOKE_WITHDRAWAL_PROPOSAL));

        verifyNoInteractions(writeBack);
    }

    /** Opoziv iznajmljivača oslobađa objekt jednako kao povlačenje. */
    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"ACTIVE", "SUSPENSION_PROPOSED", "SUSPENDED"})
    void revocation_fromAnyStandingStatus_clearsRnInEturizam(RnStatus from) {
        listener.onLifecycleChange(event(from, RnStatus.REVOKED, RnTrigger.REVOCATION));

        verify(writeBack).clear(RN);
    }

    /** Suspendiran broj i dalje je broj objekta — novi se ne smije tražiti. */
    @Test
    void suspension_keepsRnInEturizam() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnTrigger.INSPECTION));

        verifyNoInteractions(writeBack);
    }
}
