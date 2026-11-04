package com.str.backend.attachment;

/** Uz što je prilog vezan — {@code pismeno_prilog.vrsta_vlasnika} (changeset 148). */
public enum VrstaVlasnika {

    /** Zahtjev za RB; vlasnik je {@code submission_id}. U eGOP ide uz ulazno pismeno zahtjeva. */
    SUBMISSION,

    /**
     * Prijelaz RB-a ({@code registration_number_log.log_id}): opoziv iznajmljivača ili prijedlog
     * suspenzije i povlačenje službenika (suspenzija istekom roka priloge nema). U eGOP ide uz
     * pismeno tog prijelaza.
     */
    RN_LOG,

    /** Prigovor ({@code suspension_objection.objection_id}); ostaje samo u STR-u, kao i prigovor. */
    OBJECTION
}
