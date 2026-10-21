package com.str.backend.kp;

import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KpPrimateljTest {

    private static final String IZVOR = "001";

    @Test
    void naturalPerson_receivesInCitizenPretinac() {
        LessorEntity lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("12345678903");

        assertEquals(Optional.of(new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null)),
                KpPrimatelj.resolve(lessor, IZVOR));
    }

    /**
     * Odluka 21. 10. 2026.: pravnoj osobi akt ide u poslovni KP tvrtke (JIPS = OIB tvrtke), ne
     * u osobni pretinac zastupnika — pa ni pravna osoba bez zastupnika više nije anomalija.
     */
    @Test
    void legalEntity_receivesInBusinessPretinacOfCompany() {
        LessorEntity lessor = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("98765432106");
        lessor.applyNiasLegalEntity("TESTNA TVRTKA d.o.o.", null, "12345678903", "Pero Perić", null);

        assertEquals(Optional.of(new KpPrimatelj(KpPrimatelj.Tip.PS, "98765432106", IZVOR)),
                KpPrimatelj.resolve(lessor, IZVOR));
    }

    @Test
    void legalEntity_withoutRepresentative_stillReceivesInBusinessPretinac() {
        LessorEntity lessor = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("98765432106");
        lessor.applyNiasLegalEntity("TESTNA TVRTKA d.o.o.", null, null, "Pero Perić", null);

        assertEquals(KpPrimatelj.Tip.PS, KpPrimatelj.resolve(lessor, IZVOR).orElseThrow().tip());
    }

    /** Non-EU nema pretinac — dostava ostaje e-poštom, i za pravnu osobu kad zastupnik ima OIB. */
    @Test
    void nonEu_hasNoPretinac() {
        assertEquals(Optional.empty(), KpPrimatelj.resolve(nonEu(), IZVOR));

        LessorEntity company = nonEu();
        company.setRepresentativeOib("98765432106");
        company.applyLegalEntityOwner("Foreign Ltd", 276, "Berlin", "HRB 12345");
        assertEquals(Optional.empty(), KpPrimatelj.resolve(company, IZVOR));
    }

    private static LessorEntity nonEu() {
        return LessorEntity.createNonEuRegistration("John", "Doe", "Main St 1",
                "john@example.com", "john@example.com", "hash",
                LocalDate.of(1980, 1, 1), 276, "DE123", "+49123");
    }
}
