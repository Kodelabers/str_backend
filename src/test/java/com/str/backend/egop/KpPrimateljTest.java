package com.str.backend.egop;

import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KpPrimateljTest {

    @Test
    void naturalPerson_receivesInOwnPretinac() {
        LessorEntity lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("12345678903");

        assertEquals(Optional.of("12345678903"), KpPrimatelj.resolve(lessor));
        assertFalse(KpPrimatelj.isAnomaly(lessor));
    }

    /** Odluka 19. 10. 2026.: pravnoj osobi akt ide u pretinac zastupnika iz zahtjeva, ne tvrtke. */
    @Test
    void legalEntity_receivesInRepresentativesPretinac() {
        LessorEntity lessor = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("12345678903");
        lessor.applyNiasLegalEntity("TESTNA TVRTKA d.o.o.", null, "98765432106", "Pero Perić", null);

        assertEquals(Optional.of("98765432106"), KpPrimatelj.resolve(lessor));
    }

    @Test
    void legalEntity_withoutRepresentative_isAnomalyAndSkipped() {
        LessorEntity lessor = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("12345678903");
        lessor.applyNiasLegalEntity("TESTNA TVRTKA d.o.o.", null, null, "Pero Perić", null);

        assertEquals(Optional.empty(), KpPrimatelj.resolve(lessor));
        assertTrue(KpPrimatelj.isAnomaly(lessor));
    }

    /** Non-EU nema pretinac — dostava ostaje e-poštom, i za pravnu osobu kad zastupnik ima OIB. */
    @Test
    void nonEu_hasNoPretinac() {
        LessorEntity person = nonEu();
        assertEquals(Optional.empty(), KpPrimatelj.resolve(person));
        assertFalse(KpPrimatelj.isAnomaly(person));

        LessorEntity company = nonEu();
        company.setRepresentativeOib("98765432106");
        company.applyLegalEntityOwner("Foreign Ltd", 276, "Berlin", "HRB 12345");
        assertEquals(Optional.empty(), KpPrimatelj.resolve(company));
        assertFalse(KpPrimatelj.isAnomaly(company));
    }

    private static LessorEntity nonEu() {
        return LessorEntity.createNonEuRegistration("John", "Doe", "Main St 1",
                "john@example.com", "john@example.com", "hash",
                LocalDate.of(1980, 1, 1), 276, "DE123", "+49123");
    }
}
