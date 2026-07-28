package com.str.backend.rn;

import com.str.backend.domain.RnStatus;

import java.util.List;

/** STR wireframe §12 / §13 public registry view filter — maps to one or more RnStatus values. */
public enum RnRegistryView {

    ALL(List.of(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED, RnStatus.WITHDRAWN)),
    /** RB-ovi koji vrijede za oglašavanje — prijedlog suspenzije rok tek otvara, ne oduzima RB. */
    ACTIVE(List.of(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED)),
    /** Radna lista predmeta u tijeku: RB je i dalje valjan, ali rok za očitovanje teče. */
    SUSPENSION_PROPOSED(List.of(RnStatus.SUSPENSION_PROPOSED)),
    SUSPENDED(List.of(RnStatus.SUSPENDED)),
    /** STR-1.5: povučeni/opozvani RB-ovi. */
    WITHDRAWN(List.of(RnStatus.WITHDRAWN)),
    /** STR-1.5: svi za oglašavanje nevažeći RB-ovi (suspendirani + povučeni). */
    INVALID(List.of(RnStatus.SUSPENDED, RnStatus.WITHDRAWN));

    private final List<RnStatus> statuses;

    RnRegistryView(List<RnStatus> statuses) {
        this.statuses = statuses;
    }

    public List<RnStatus> statuses() {
        return statuses;
    }
}
