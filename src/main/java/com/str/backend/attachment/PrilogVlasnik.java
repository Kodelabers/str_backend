package com.str.backend.attachment;

import java.util.Objects;
import java.util.UUID;

/** Vlasnik priloga: vrsta i id zapisa uz koji je prilog vezan. */
public record PrilogVlasnik(VrstaVlasnika vrsta, UUID id) {

    public PrilogVlasnik {
        Objects.requireNonNull(vrsta, "vrsta");
        Objects.requireNonNull(id, "id");
    }

    public static PrilogVlasnik zahtjev(UUID submissionId) {
        return new PrilogVlasnik(VrstaVlasnika.SUBMISSION, submissionId);
    }

    public static PrilogVlasnik prijelaz(UUID rnLogId) {
        return new PrilogVlasnik(VrstaVlasnika.RN_LOG, rnLogId);
    }

    public static PrilogVlasnik prigovor(UUID objectionId) {
        return new PrilogVlasnik(VrstaVlasnika.OBJECTION, objectionId);
    }
}
