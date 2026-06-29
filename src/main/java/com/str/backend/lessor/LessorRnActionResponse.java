package com.str.backend.lessor;

import com.str.backend.domain.RnStatus;

/** Response for lessor-initiated RN actions (opoziv) — mirrors the frontend LessorRnActionResponse. */
public record LessorRnActionResponse(
        String rn,
        RnStatus status
) {}
