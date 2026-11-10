package com.str.backend.rn;

import com.str.backend.domain.RnStatus;

import java.util.List;

/**
 * STR wireframe §12 / §13 public registry view filter — maps to one or more RnStatus values.
 *
 * <p>Pogledi valjanosti ({@link #ACTIVE}, {@link #INVALID}) uz to gledaju i prijedlog povlačenja pokrenut
 * iz suspenzije (T12, {@code withdrawal_from_suspended}): on javno ostaje suspendiran, pa ga ACTIVE ne
 * uključuje, a INVALID uključuje ({@link #validity()}). Radna lista {@link #WITHDRAWAL_PROPOSED} ga uključuje.
 */
public enum RnRegistryView {

    ALL(List.of(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED,
            RnStatus.WITHDRAWAL_PROPOSED, RnStatus.REVOKED, RnStatus.WITHDRAWN)),
    /**
     * RB-ovi koji vrijede za oglašavanje — prijedlog suspenzije i prijedlog povlačenja rok tek otvaraju,
     * ne oduzimaju RB.
     */
    ACTIVE(List.of(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED, RnStatus.WITHDRAWAL_PROPOSED)),
    /** Radna lista predmeta u tijeku: RB je i dalje valjan, ali rok za očitovanje teče. */
    SUSPENSION_PROPOSED(List.of(RnStatus.SUSPENSION_PROPOSED)),
    SUSPENDED(List.of(RnStatus.SUSPENDED)),
    /** T12: radna lista prijedloga povlačenja — RB je i dalje valjan, a rok za očitovanje teče. */
    WITHDRAWAL_PROPOSED(List.of(RnStatus.WITHDRAWAL_PROPOSED)),
    /** STR-1.3: RB-ovi koje je iznajmljivač sam opozvao. */
    REVOKED(List.of(RnStatus.REVOKED)),
    /** STR-1.5: RB-ovi koje je nadležno tijelo povuklo po službenoj dužnosti. */
    WITHDRAWN(List.of(RnStatus.WITHDRAWN)),
    /** STR-1.5: svi za oglašavanje nevažeći RB-ovi (suspendirani + opozvani + povučeni). */
    INVALID(List.of(RnStatus.SUSPENDED, RnStatus.REVOKED, RnStatus.WITHDRAWN));

    /** Vrijednosti {@link #validity()} — parametar {@code :validity} upita registra. */
    public static final String VALID_ONLY = "VALID";
    public static final String INVALID_TOO = "INVALID";

    private final List<RnStatus> statuses;

    RnRegistryView(List<RnStatus> statuses) {
        this.statuses = statuses;
    }

    public List<RnStatus> statuses() {
        return statuses;
    }

    /**
     * Kako pogled tretira prijedlog povlačenja iz suspenzije: {@link #VALID_ONLY} ga isključuje (ACTIVE),
     * {@link #INVALID_TOO} ga dodaje (INVALID), {@code null} gleda samo stvarni status.
     */
    public String validity() {
        return switch (this) {
            case ACTIVE -> VALID_ONLY;
            case INVALID -> INVALID_TOO;
            default -> null;
        };
    }
}
