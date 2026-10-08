package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Go3LegalityCheckTest {

    private final Go3LegalityCheck step = new Go3LegalityCheck();

    @Test
    void passes_whenAccommodationLegalized() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        assertThat(step.check(ctx(acc))).isInstanceOf(ValidationResult.Passed.class);
    }

    /**
     * GO-3 je isključen u {@code 629f86f} (uvijek {@code Passed}) dok ne postoji stvarni izvor
     * podatka o legalnosti — {@code docs/STR-NEDOSTAJUCE-FUNKCIONALNOSTI.md}, B10 i BX6. Test
     * opisuje ponašanje koje se vraća s tim izvorom.
     */
    @Disabled("GO-3 isključen u 629f86f dok ne postoji izvor legalnosti (STR-NEDOSTAJUCE-FUNKCIONALNOSTI B10/BX6)")
    @Test
    void rejects_whenAccommodationNotLegalized() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, false);
        assertThat(step.check(ctx(acc))).isInstanceOf(ValidationResult.Rejected.class);
    }

    private ValidationContext ctx(AccommodationEntity acc) {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        return new ValidationContext(acc, lessor);
    }
}
