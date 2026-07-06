package com.str.backend.lessor;

import jakarta.validation.constraints.Size;

/**
 * Body for lessor-initiated revocation (opoziv) of their own RN. The reason is optional —
 * the frontend sends {@code {}} when none is given (see lessorApi.withdrawOwnRegistration).
 */
public record LessorWithdrawRequest(
        @Size(max = 1024) String reason
) {}
