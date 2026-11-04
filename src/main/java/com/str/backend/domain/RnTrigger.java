package com.str.backend.domain;

import java.util.List;

public enum RnTrigger {
    /**
     * Povijesni razlog suspenzije („istek suglasnosti suvlasnika"). Više se ne nudi i ne prihvaća
     * za nove prijedloge (MINTS, konačan popis razloga), ali ostaje u enumu zbog postojećih
     * zapisa u {@code registration_number_log} i natpisa u već izdanim aktima.
     */
    CONSENT_EXPIRY,
    /** Za objekt nije izdano rješenje. */
    NO_DECISION,
    /** Rješenje je ukinuto. */
    DECISION_REVOKED,
    INSPECTION,
    INCOMPLETE_DOCUMENTATION,
    OTHER,
    /** Povlačenje po službenoj dužnosti → {@link RnStatus#WITHDRAWN}. Samo službenik. */
    WITHDRAWAL,
    /** Opoziv na zahtjev samog iznajmljivača → {@link RnStatus#REVOKED}. Samo iznajmljivač. */
    REVOCATION,
    REACTIVATE,
    REVOKE_PROPOSAL,
    DEADLINE_EXCEEDED;

    /**
     * Razlozi prijedloga suspenzije koje službenik bira, redom kojim se nude (MINTS). Jedini
     * izvor za {@code RnService.suspend} i šifrarnik {@code GET /api/lookups/rn-triggers}, pa se
     * ponuđeno i prihvaćeno ne mogu razići.
     */
    public static final List<RnTrigger> SUSPENSION_REASONS = List.of(
            NO_DECISION, DECISION_REVOKED, INCOMPLETE_DOCUMENTATION, INSPECTION, OTHER);
}
