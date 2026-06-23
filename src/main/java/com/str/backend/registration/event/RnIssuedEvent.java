package com.str.backend.registration.event;

import java.util.UUID;

/**
 * Published after the registration number has been issued and the surrounding
 * persistence committed. Listeners deferred via {@code TransactionPhase.AFTER_COMMIT}
 * generate the PDF and route it to eGOP (EU lessor) or e-mail (non-EU lessor).
 *
 * <p>Failures inside the listener are logged but do not affect the RN — by spec
 * the registration is valid regardless of delivery success.
 */
public record RnIssuedEvent(
        UUID submissionId,
        UUID accommodationId,
        UUID lessorId,
        String rn,
        String countyName,
        String typeName,
        String postalCode
) {
}
