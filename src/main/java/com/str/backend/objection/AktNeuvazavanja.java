package com.str.backend.objection;

import com.str.backend.domain.RnStatus;

/**
 * Koji akt izdaje neuvažavanje podneska (UAT 25.11.2026) — po tome sučelje službeniku nudi KLASU i URBROJ,
 * priloge i Rješenje, a backend ih traži.
 */
public enum AktNeuvazavanja {

    /** Neuvaženo očitovanje na prijedlog u kojem je RB i sada: obavijest o suspenziji odnosno povlačenju. */
    MJERA,

    /**
     * Neuvaženi prigovor na mjeru u kojoj je RB i sada: obavijest o neprihvaćanju prigovora, uz obavezno Rješenje
     * službenika.
     */
    NEPRIHVACANJE,

    /**
     * Akta nema: podnesak je zaostao iz statusa koji je RB u međuvremenu napustio (npr. očitovanje na prijedlog
     * nakon što je mjera nastupila istekom roka) — neuvažavanje samo bilježi odluku.
     */
    NEMA;

    /**
     * @param trenutni status RB-a sada
     * @param naPredaji status RB-a kad je podnesak predan ({@code suspension_objection.rn_status})
     */
    public static AktNeuvazavanja za(RnStatus trenutni, RnStatus naPredaji) {
        if (trenutni == null || trenutni != naPredaji) {
            return NEMA;
        }
        return switch (trenutni) {
            case SUSPENSION_PROPOSED, WITHDRAWAL_PROPOSED -> MJERA;
            case SUSPENDED, WITHDRAWN -> NEPRIHVACANJE;
            default -> NEMA;
        };
    }
}
