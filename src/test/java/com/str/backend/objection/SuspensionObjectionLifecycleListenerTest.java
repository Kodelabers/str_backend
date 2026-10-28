package com.str.backend.objection;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SuspensionObjectionLifecycleListenerTest {

    private static final String RN = "HR120001000000000123";

    private final SuspensionObjectionRepository repository = mock(SuspensionObjectionRepository.class);
    private final SuspensionObjectionLifecycleListener listener = new SuspensionObjectionLifecycleListener(repository);

    private SuspensionObjectionEntity objection;

    @BeforeEach
    void submittedObjection() {
        objection = SuspensionObjectionEntity.createForOib("99999999990", RN, RnStatus.SUSPENSION_PROPOSED, null,
                "NIAS:99999999990", "prigovor.pdf", "application/pdf",
                "%PDF".getBytes(StandardCharsets.US_ASCII));
        when(repository.findByRnAndStatus(RN, SuspensionObjectionStatus.SUBMITTED)).thenReturn(List.of(objection));
    }

    /** RB je napustio prijedlog i suspenziju drugim putem — o prigovoru se više nema što odlučiti. */
    @ParameterizedTest
    @CsvSource({
            "SUSPENSION_PROPOSED, ACTIVE, REVOKE_PROPOSAL",
            "SUSPENDED, ACTIVE, REACTIVATE",
            "SUSPENSION_PROPOSED, WITHDRAWN, WITHDRAWAL",
            "SUSPENDED, REVOKED, REVOCATION"
    })
    void leavingProposalOrSuspension_closesSubmittedObjection(RnStatus from, RnStatus to, String trigger) {
        listener.onTransition(event(from, to, trigger));

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.CLOSED);
        assertThat(objection.getResolvedBy()).isEqualTo(SuspensionObjectionLifecycleListener.SYSTEM_ACTOR);
        assertThat(objection.getResolvedAt()).isNotNull();
    }

    /** Prigovor ne zaustavlja rok: po isteku RB prelazi u suspenziju, a prigovor ostaje otvoren. */
    @Test
    void deadlineExceeded_keepsObjectionOpen() {
        listener.onTransition(event(RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED, "DEADLINE_EXCEEDED"));

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        verifyNoInteractions(repository);
    }

    private static RnLifecycleEvent event(RnStatus from, RnStatus to, String trigger) {
        return new RnLifecycleEvent(UUID.randomUUID(), RN, from, to, RnTrigger.valueOf(trigger), null, null);
    }
}
