package com.str.backend.rn.event;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;

import java.util.UUID;

/**
 * Promjena statusa registracijskog broja, objavljena iz
 * {@code RnStatusTransitionService#transition} — jedine točke kroz koju status smije proći.
 * Zato nijedan put (voditelj postupka, iznajmljivač, izdavanje) ne može zaobići obavijest.
 *
 * @param logId identitet zapisa u {@code registration_number_log}; služi kao {@code act_ref}
 *              pri urudžbiranju, po kojem se ponovljena suspenzija istog RB-a razlikuje od
 *              prethodne
 * @param actor tko je promjenu izazvao; {@code "LESSOR:<uuid>"} razlikuje opoziv od
 *              povlačenja po službenoj dužnosti — oba su {@link RnTrigger#WITHDRAWAL}
 */
public record RnLifecycleEvent(
        UUID logId,
        String rn,
        RnStatus from,
        RnStatus to,
        RnTrigger trigger,
        String actor,
        String reason
) {

    private static final String LESSOR_PREFIX = "LESSOR:";

    /** Opoziv na zahtjev iznajmljivača, za razliku od povlačenja po službenoj dužnosti. */
    public boolean initiatedByLessor() {
        return actor != null && actor.startsWith(LESSOR_PREFIX);
    }
}
