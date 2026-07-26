package com.str.backend.registration.event;

import java.util.UUID;

/**
 * Published after the registration number has been issued and the surrounding
 * persistence committed. Listeners deferred via {@code TransactionPhase.AFTER_COMMIT}
 * file the request in eGOP and (for non-EU lessors) e-mail the PDF.
 *
 * <p>Carries identifiers only: {@code EgopRegistrationDispatcher} has to re-read the
 * accommodation, lessor, county, type and postal code from the database anyway so that
 * the same path works for a later retry. {@code rn} is kept because it is logged at the
 * publishing site and used before the entities are loaded.
 *
 * <p>Failures inside the listener are logged but do not affect the RN — by spec
 * the registration is valid regardless of delivery success.
 */
public record RnIssuedEvent(
        UUID submissionId,
        String rn
) {
}
