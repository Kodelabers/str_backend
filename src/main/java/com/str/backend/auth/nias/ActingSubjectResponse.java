package com.str.backend.auth.nias;

import java.time.Instant;
import java.util.List;

/**
 * Pravna osoba u čije ime korisnik djeluje, s podacima o zastupniku, potvrđena kroz e-Ovlaštenja.
 *
 * @param funkcije funkcije zastupnika iz e-Zastupanja (npr. „Direktor")
 * @param izvor    uvijek {@code E_OVLASTENJA}
 */
public record ActingSubjectResponse(
        String oib,
        String naziv,
        List<String> funkcije,
        String zastupnikOib,
        String zastupnikIme,
        String zastupnikPrezime,
        Instant provjereno,
        String izvor
) {

    static ActingSubjectResponse of(ActingSubject s) {
        return new ActingSubjectResponse(s.legalOib(), s.legalName(), s.functions(),
                s.representativeOib(), s.representativeFirstName(), s.representativeLastName(),
                s.verifiedAt(), "E_OVLASTENJA");
    }
}
