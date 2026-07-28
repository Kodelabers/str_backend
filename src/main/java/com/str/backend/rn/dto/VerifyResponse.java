package com.str.backend.rn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.str.backend.domain.RnStatus;

/**
 * Public registry verification result (STR-1.4-001, čl. 4. st. 5. STR Uredbe).
 *
 * <ul>
 *   <li>ACTIVE + verified → {@code valid=true}, {@code status=ACTIVE} + advertise-safe object data.</li>
 *   <li>SUSPENSION_PROPOSED + verified → same payload, {@code status=SUSPENSION_PROPOSED}: the
 *       registration number still holds while the response deadline runs, so it verifies like an
 *       active one; the status is reported truthfully rather than folded into ACTIVE.</li>
 *   <li>SUSPENDED → {@code valid=true}, {@code status=SUSPENDED}, no object data
 *       (invalid for advertising).</li>
 *   <li>WITHDRAWN (opozvan) or non-existent → {@code valid=false} only — privacy: a withdrawn RN
 *       must not reveal it ever existed, so the response is identical to not-found.</li>
 * </ul>
 *
 * Null fields are omitted from the JSON so the legacy {@code {valid}}-only shape is preserved
 * for the not-found / suspended cases and the frontend stays backward-compatible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifyResponse(
        boolean valid,
        RnStatus status,
        String registrationNumber,
        String accommodationName,
        String category,
        String address,
        String group,
        String type
) {

    /** Withdrawn or non-existent — intentionally indistinguishable. */
    public static VerifyResponse invalid() {
        return new VerifyResponse(false, null, null, null, null, null, null, null);
    }

    /** Suspended — valid but invalid for advertising; no object data exposed. */
    public static VerifyResponse suspended() {
        return new VerifyResponse(true, RnStatus.SUSPENDED, null, null, null, null, null, null);
    }

    /**
     * Valid for advertising — full advertise-safe object data. The status is passed in because
     * both {@link RnStatus#ACTIVE} and {@link RnStatus#SUSPENSION_PROPOSED} land here.
     */
    public static VerifyResponse active(RnStatus status, String registrationNumber, String accommodationName,
                                        String category, String address, String group, String type) {
        return new VerifyResponse(true, status, registrationNumber,
                accommodationName, category, address, group, type);
    }
}
