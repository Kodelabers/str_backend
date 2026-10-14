package com.str.backend.domain;

public enum RnTrigger {
    CONSENT_EXPIRY,
    INSPECTION,
    INCOMPLETE_DOCUMENTATION,
    OTHER,
    /** Povlačenje po službenoj dužnosti → {@link RnStatus#WITHDRAWN}. Samo službenik. */
    WITHDRAWAL,
    /** Opoziv na zahtjev samog iznajmljivača → {@link RnStatus#REVOKED}. Samo iznajmljivač. */
    REVOCATION,
    REACTIVATE,
    REVOKE_PROPOSAL,
    DEADLINE_EXCEEDED
}
