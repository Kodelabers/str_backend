package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.core.CoreObjektEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Go3LegalnostObjektaTest {

    private final Go3LegalnostObjekta step = new Go3LegalnostObjekta();

    @Test
    void passes_whenAccommodationLegalizedAndNoCore() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        assertThat(step.check(ctx(acc, null))).isInstanceOf(ValidationResult.Passed.class);
    }

    @Test
    void passes_whenAccommodationLegalizedAndCoreLegal() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        CoreObjektEntity core = GoTestFixtures.coreObject(4, 8, true);
        assertThat(step.check(ctx(acc, core))).isInstanceOf(ValidationResult.Passed.class);
    }

    @Test
    void rejects_whenAccommodationNotLegalized() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, false);
        assertThat(step.check(ctx(acc, null))).isInstanceOf(ValidationResult.Rejected.class);
    }

    @Test
    void rejects_whenCoreNotLegal() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        CoreObjektEntity core = GoTestFixtures.coreObject(4, 8, false);
        assertThat(step.check(ctx(acc, core))).isInstanceOf(ValidationResult.Rejected.class);
    }

    private ValidationContext ctx(AccommodationEntity acc, CoreObjektEntity core) {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        return new ValidationContext(acc, lessor, core);
    }
}
