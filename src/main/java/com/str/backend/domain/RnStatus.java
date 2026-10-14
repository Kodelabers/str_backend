package com.str.backend.domain;

public enum RnStatus {
    ACTIVE,
    SUSPENSION_PROPOSED,
    SUSPENDED,
    /** Opozvan: iznajmljivač je sam opozvao RB (čl. 5. st. 5 STR Uredbe). Konačan. */
    REVOKED,
    /** Povučen: mjera nadležnog tijela po službenoj dužnosti (čl. 6 STR Uredbe). Konačan. */
    WITHDRAWN;

    public boolean canTransitionTo(RnStatus target, RnTrigger trigger) {
        return switch (this) {
            case ACTIVE -> switch (trigger) {
                // OTHER je slobodan razlog: RnService#suspend uz njega traži obrazloženje, koje
                // onda nosi izreku prijedloga umjesto šifriranog razloga.
                case CONSENT_EXPIRY, INSPECTION, INCOMPLETE_DOCUMENTATION, OTHER -> target == SUSPENSION_PROPOSED;
                case REVOCATION -> target == REVOKED;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            case SUSPENSION_PROPOSED -> switch (trigger) {
                case REVOKE_PROPOSAL -> target == ACTIVE;
                case DEADLINE_EXCEEDED -> target == SUSPENDED;
                case REVOCATION -> target == REVOKED;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            case SUSPENDED -> switch (trigger) {
                case REACTIVATE -> target == ACTIVE;
                case REVOCATION -> target == REVOKED;
                case WITHDRAWAL -> target == WITHDRAWN;
                default -> false;
            };
            // Dva konačna statusa: opoziv iznajmljivača (REVOKED) i povlačenje po službenoj
            // dužnosti (WITHDRAWN) su trajni — reaktivacije nema, a jedan ne prelazi u drugi.
            case REVOKED, WITHDRAWN -> false;
        };
    }

    /**
     * SUSPENSION_PROPOSED je međukorak, ne suspenzija: dok rok za očitovanje teče RB vrijedi i
     * smije se oglašavati, pa se svugdje izvan samog postupka broji i prikazuje kao aktivan.
     * Suspenzija nastupa tek istekom roka ({@link RnTrigger#DEADLINE_EXCEEDED}).
     */
    public boolean isActiveForPublicUse() {
        return this == ACTIVE || this == SUSPENSION_PROPOSED;
    }

    /**
     * Konačan status — opozvan ili povučen. RB više ne pripada objektu: objekt smije dobiti novi
     * broj, a podaci se čuvaju do isteka retencije ({@code WithdrawnRnRetentionJob}).
     */
    public boolean isTerminal() {
        return this == REVOKED || this == WITHDRAWN;
    }
}
