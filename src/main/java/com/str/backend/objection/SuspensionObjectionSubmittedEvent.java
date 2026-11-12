package com.str.backend.objection;

import com.str.backend.domain.RnStatus;

import java.util.UUID;

/**
 * Iznajmljivač je predao očitovanje ili prigovor ({@link SuspensionObjectionService#submit}). Objavljuje se u
 * transakciji predaje; {@code egop.PodnesakFilingListener} ga nakon commita zapisuje kao ulazno pismeno u
 * eGOP-u.
 *
 * @param rnStatus status broja pri predaji — o njemu ovisi vrsta pismena
 * @param rnLogId  zapis prijelaza u {@code rnStatus}, tj. akt na koji podnesak odgovara; {@code null} kad
 *                 zapisa nema
 */
public record SuspensionObjectionSubmittedEvent(UUID objectionId, String rn, RnStatus rnStatus, UUID rnLogId) {
}
