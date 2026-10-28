package com.str.backend.auth.nias;

import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.str.StrSubjectRepository;
import com.str.backend.str.StrSubjectRepository.DocumentContactRow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Redoslijed izvora kontakta za obrazac: zadnji RB, pa dokument iz eTurizma. */
class SubjectContactResolverTest {

    private static final String OIB = "12312312316";
    private static final String LEGAL_OIB = "33333333360";
    /** 18 mjeseci prije „danas” iz sata niže. */
    private static final LocalDate CUTOFF = LocalDate.of(2025, 4, 28);

    private final LessorRepository lessors = mock(LessorRepository.class);
    private final StrSubjectRepository documents = mock(StrSubjectRepository.class);
    private final SubjectContactResolver resolver = new SubjectContactResolver(lessors, documents,
            Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC), 18);

    private static LessorEntity snapshot(String email, String name, String phone, String mobile) {
        LessorEntity l = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        l.applyContact(email, name, phone, mobile);
        return l;
    }

    private static DocumentContactRow document() {
        return new DocumentContactRow() {
            public String getName() { return "Recepcija"; }
            public String getPhone() { return "012345678"; }
            public String getMobile() { return "0911234567"; }
            public String getEmail() { return "info@dokument.hr"; }
        };
    }

    @Test
    void rbSnapshot_isTakenWhole_documentNotRead() {
        when(lessors.findLatestRbSnapshot(OIB, null, null, CUTOFF))
                .thenReturn(Optional.of(snapshot("ana@example.hr", null, null, "0981111111")));

        SubjectContact c = resolver.forPerson(OIB, null, null);

        assertThat(c).isEqualTo(new SubjectContact("0981111111", null, null, "ana@example.hr", SubjectContact.Source.RB));
        verify(documents, never()).findDocumentContactByOib(any());
    }

    /** Prazni i razmaknuti parametri ne smiju dati prednost objektu {@code ""}; rok je isti kao za ponovni zahtjev. */
    @Test
    void blankObjectParams_areIgnored_valuesTrimmed_retentionCutoffPassed() {
        resolver.forPerson(OIB, "  ", " HR12345678 ");

        verify(lessors).findLatestRbSnapshot(OIB, null, "HR12345678", CUTOFF);
    }

    /** RB iz vremena prije obaveznog mobitela ne računa se — ni djelomično, da se ne miješaju izvori. */
    @Test
    void rbWithoutMobile_isIgnored_documentUsed() {
        when(lessors.findLatestRbSnapshot(OIB, null, null, CUTOFF))
                .thenReturn(Optional.of(snapshot("ana@example.hr", "Ana", null, " ")));
        when(documents.findDocumentContactByOib(OIB)).thenReturn(Optional.of(document()));

        SubjectContact c = resolver.forPerson(OIB, null, null);

        assertThat(c).isEqualTo(new SubjectContact("0911234567", "012345678", "Recepcija", "info@dokument.hr",
                SubjectContact.Source.DOKUMENT));
    }

    /** Prazan stari snapshot ne smije nositi izvor {@code RB} — obrazac bi njime potisnuo kontakt objekta. */
    @Test
    void emptyRbSnapshot_withoutDocument_isNone() {
        when(lessors.findLatestRbSnapshot(OIB, null, null, CUTOFF))
                .thenReturn(Optional.of(snapshot(null, null, null, null)));

        assertThat(resolver.forPerson(OIB, null, null)).isEqualTo(SubjectContact.NONE);
    }

    @Test
    void noRb_fallsBackToDocument() {
        when(documents.findDocumentContactByOib(OIB)).thenReturn(Optional.of(document()));

        SubjectContact c = resolver.forPerson(OIB, "F-1", null);

        assertThat(c.source()).isEqualTo(SubjectContact.Source.DOKUMENT);
        assertThat(c.email()).isEqualTo("info@dokument.hr");
    }

    @Test
    void nothingFound_isEmptyWithoutSource() {
        assertThat(resolver.forPerson(OIB, null, null)).isEqualTo(SubjectContact.NONE);
    }

    /** Tvrtka: snapshot nosi kontakt tadašnjeg zastupnika, pa se RB-ovi ni ne traže. */
    @Test
    void legalEntity_usesOnlyCompanyDocument() {
        when(documents.findDocumentContactByOib(LEGAL_OIB)).thenReturn(Optional.of(document()));

        SubjectContact c = resolver.forLegalEntity(LEGAL_OIB);

        assertThat(c.source()).isEqualTo(SubjectContact.Source.DOKUMENT);
        verifyNoInteractions(lessors);
    }
}
