package com.str.backend.rn;

import com.str.backend.domain.RnStatus;

import java.util.List;

/** STR wireframe §12 / §13 public registry view filter — maps to one or more RnStatus values. */
public enum RnRegistryView {

    ALL(List.of(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnStatus.WITHDRAWN)),
    ACTIVE(List.of(RnStatus.ACTIVE)),
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
