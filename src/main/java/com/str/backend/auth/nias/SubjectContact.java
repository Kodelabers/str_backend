package com.str.backend.auth.nias;

import com.str.backend.lessor.LessorEntity;
import com.str.backend.str.StrSubjectRepository.DocumentContactRow;

/**
 * Kontakt za predpopunu obrasca za RB, s izvorom. Sva polja su {@code null} kad se ništa ne
 * pronađe — tada ga korisnik upisuje sam.
 *
 * @param source {@code RB} — snapshot sa zadnjeg izdanog RB-a; {@code DOKUMENT} —
 *               {@code str.document_contact} iz eTurizma; {@code null} kad kontakta nema
 */
public record SubjectContact(String mobile, String phone, String name, String email, Source source) {

    public enum Source { RB, DOKUMENT }

    static final SubjectContact NONE = new SubjectContact(null, null, null, null, null);

    static SubjectContact ofRb(LessorEntity l) {
        return new SubjectContact(l.getMobileNumber(), l.getPhoneNumber(), l.getContactName(), l.getEmail(), Source.RB);
    }

    static SubjectContact ofDocument(DocumentContactRow c) {
        return c == null ? NONE : new SubjectContact(c.getMobile(), c.getPhone(), c.getName(), c.getEmail(), Source.DOKUMENT);
    }
}
