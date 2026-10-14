package com.str.backend.rn.event;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;

import java.util.UUID;

/**
 * Promjena statusa registracijskog broja, objavljena iz
 * {@code RnStatusTransitionService#transition} — jedine točke kroz koju status smije proći.
 * Zato nijedan put (voditelj postupka, iznajmljivač) ne može zaobići obavijest. Izdavanje nije
 * prijelaz — RB nastaje odmah kao {@code ACTIVE} — pa ovaj event ne objavljuje.
 *
 * @param logId identitet zapisa u {@code registration_number_log}; služi kao {@code act_ref}
 *              pri urudžbiranju, po kojem se ponovljena suspenzija istog RB-a razlikuje od
 *              prethodne
 * @param actor tko je promjenu izazvao — revizijski trag: {@code "LESSOR:<uuid>"} (prijava
 *              lozinkom) ili {@code "NIAS:<oib>"} za opoziv iznajmljivača, {@code null} za
 *              službenika i sustav. Akt se po njemu više ne bira: opoziv i povlačenje imaju
 *              vlastite statuse ({@link RnStatus#REVOKED} / {@link RnStatus#WITHDRAWN}).
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
}
