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
 * @param actor tko je promjenu izazvao; {@code "LESSOR:<uuid>"} odnosno {@code "NIAS:<oib>"}
 *              razlikuje opoziv od povlačenja po službenoj dužnosti — oba su
 *              {@link RnTrigger#WITHDRAWAL}
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

    /** Prijava lozinkom — {@code LessorRnActionService#withdrawOwn}. */
    private static final String LESSOR_PREFIX = "LESSOR:";

    /** Prijava preko NIAS-a — {@code LessorRnActionService#withdrawOwnByOib}. */
    private static final String NIAS_PREFIX = "NIAS:";

    /**
     * Opoziv na zahtjev iznajmljivača, za razliku od povlačenja po službenoj dužnosti.
     *
     * <p>Oba prefiksa znače istu stvar — sam iznajmljivač — a razlikuju se samo po kanalu
     * prijave; u {@code actor} polju ostaju odvojeni jer revizijski trag taj kanal mora
     * pokazati. Ranije se provjeravao samo {@code LESSOR:}, pa je opoziv preko NIAS-a (a to je
     * produkcijski put prijave) dobivao „Obavijest o povlačenju" — akt po službenoj dužnosti,
     * s uputom o pravnom lijeku koja na vlastiti zahtjev stranke ne ide.
     */
    public boolean initiatedByLessor() {
        return actor != null
                && (actor.startsWith(LESSOR_PREFIX) || actor.startsWith(NIAS_PREFIX));
    }
}
