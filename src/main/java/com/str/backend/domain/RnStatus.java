package com.str.backend.domain;

public enum RnStatus {
    ACTIVE,
    SUSPENSION_PROPOSED,
    SUSPENDED,
    /**
     * Predložen za povlačenje (T12): stranka je pozvana na očitovanje, a broj se povlači tek istekom
     * roka ({@link RnTrigger#WITHDRAWAL_DEADLINE_EXCEEDED}) — noćnim poslom ili kad službenik nakon roka
     * ne uvaži očitovanje. Kao i prijedlog suspenzije, broj dok rok teče vrijedi.
     */
    WITHDRAWAL_PROPOSED,
    /** Opozvan: iznajmljivač je sam opozvao RB (čl. 5. st. 5 STR Uredbe). Konačan. */
    REVOKED,
    /**
     * Povučen: mjera nadležnog tijela po službenoj dužnosti (čl. 6 STR Uredbe). Konačan; jedini
     * izlaz je uvaženi prigovor stranke ({@link RnTrigger#REINSTATE}).
     */
    WITHDRAWN;

    public boolean canTransitionTo(RnStatus target, RnTrigger trigger) {
        return switch (this) {
            case ACTIVE -> switch (trigger) {
                // OTHER je slobodan razlog: RnService#suspend uz njega traži obrazloženje, koje
                // onda nosi izreku prijedloga umjesto šifriranog razloga. CONSENT_EXPIRY je
                // povijesni razlog — ostaje u tragu, ali novi prijedlog njime više ne nastaje.
                case NO_DECISION, DECISION_REVOKED, INCOMPLETE_DOCUMENTATION, INSPECTION, OTHER ->
                        target == SUSPENSION_PROPOSED;
                case REVOCATION -> target == REVOKED;
                case WITHDRAWAL_PROPOSAL -> target == WITHDRAWAL_PROPOSED;
                default -> false;
            };
            case SUSPENSION_PROPOSED -> switch (trigger) {
                case REVOKE_PROPOSAL -> target == ACTIVE;
                case DEADLINE_EXCEEDED -> target == SUSPENDED;
                case REVOCATION -> target == REVOKED;
                case WITHDRAWAL_PROPOSAL -> target == WITHDRAWAL_PROPOSED;
                default -> false;
            };
            case SUSPENDED -> switch (trigger) {
                case REACTIVATE -> target == ACTIVE;
                case REVOCATION -> target == REVOKED;
                case WITHDRAWAL_PROPOSAL -> target == WITHDRAWAL_PROPOSED;
                default -> false;
            };
            // Povlačenje je dvofazno kao suspenzija i, kao ni suspenzija, nema ručnog prijelaza: broj se
            // povlači samo istekom roka. Obustava uvijek vraća broj u ACTIVE (odluka korisnika, T12) —
            // postupak suspenzije koji je bio u tijeku time završava.
            case WITHDRAWAL_PROPOSED -> switch (trigger) {
                case WITHDRAWAL_DEADLINE_EXCEEDED -> target == WITHDRAWN;
                case REVOKE_WITHDRAWAL_PROPOSAL -> target == ACTIVE;
                case REVOCATION -> target == REVOKED;
                default -> false;
            };
            // Uvaženi prigovor na povučen broj vraća ga u ACTIVE (odluka korisnika, T12).
            case WITHDRAWN -> trigger == RnTrigger.REINSTATE && target == ACTIVE;
            // Opoziv iznajmljivača je trajan i nikad ne prelazi u povlačenje.
            case REVOKED -> false;
        };
    }

    /**
     * Prijedlog suspenzije i prijedlog povlačenja su međukorak, ne mjera: dok rok za očitovanje teče
     * RB vrijedi i smije se oglašavati, pa se svugdje izvan samog postupka broji i prikazuje kao
     * aktivan. Mjera nastupa tek istekom roka ({@link RnTrigger#DEADLINE_EXCEEDED},
     * {@link RnTrigger#WITHDRAWAL_DEADLINE_EXCEEDED}).
     */
    public boolean isActiveForPublicUse() {
        return this == ACTIVE || this == SUSPENSION_PROPOSED || this == WITHDRAWAL_PROPOSED;
    }

    /**
     * Konačan status — opozvan ili povučen. RB više ne pripada objektu: objekt smije dobiti novi
     * broj, a podaci se čuvaju do isteka retencije ({@code WithdrawnRnRetentionJob}). Povučen broj
     * ostaje konačan i uz mogućnost prigovora: dok o prigovoru nije odlučeno, broj ne vrijedi.
     */
    public boolean isTerminal() {
        return this == REVOKED || this == WITHDRAWN;
    }
}
