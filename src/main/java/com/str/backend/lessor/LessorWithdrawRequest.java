package com.str.backend.lessor;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body for lessor-initiated revocation (opoziv) of their own RN. The reason is optional —
 * the frontend sends {@code {}} when none is given (see lessorApi.withdrawOwnRegistration).
 *
 * <p>{@code prilogIds}: prilozi uz zahtjev za opoziv (T17), id-evi iz staginga
 * ({@code POST /api/nias/attachments} odnosno {@code /api/lessor/attachments}); neobavezno.
 */
public record LessorWithdrawRequest(
        @Size(max = 1024) String reason,
        List<Long> prilogIds
) {

    public LessorWithdrawRequest(String reason) {
        this(reason, null);
    }
}
