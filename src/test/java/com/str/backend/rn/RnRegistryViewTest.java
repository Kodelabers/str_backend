package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry views split registration numbers into "valid for advertising" and "not", so a
 * status landing on the wrong side is what an advertiser or an inspector acts on. A proposed
 * suspension belongs on the valid side: it only opens the response deadline (čl. 30. st. 2 ZUP)
 * and the number keeps standing until that deadline expires.
 */
class RnRegistryViewTest {

    @Test
    void activeViewIncludesProposedSuspensions() {
        assertThat(RnRegistryView.ACTIVE.statuses())
                .containsExactlyInAnyOrder(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED);
    }

    @Test
    void invalidViewIsOnlySuspendedAndWithdrawn() {
        assertThat(RnRegistryView.INVALID.statuses())
                .containsExactlyInAnyOrder(RnStatus.SUSPENDED, RnStatus.WITHDRAWN)
                .doesNotContain(RnStatus.SUSPENSION_PROPOSED);
    }

    /** The proposal work list stays reachable on its own — it just isn't "invalid". */
    @Test
    void proposalsRemainReachableAsTheirOwnView() {
        assertThat(RnRegistryView.SUSPENSION_PROPOSED.statuses())
                .containsExactly(RnStatus.SUSPENSION_PROPOSED);
    }

    /** No issued status may fall out of every view — ALL is what the officer's export uses. */
    @Test
    void allViewCoversEveryIssuedStatus() {
        assertThat(RnRegistryView.ALL.statuses())
                .containsExactlyInAnyOrder(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED,
                        RnStatus.SUSPENDED, RnStatus.WITHDRAWN);
    }
}
