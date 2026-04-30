package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.core.CoreObjektEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Go5ProvjeraKapacitetaTest {

    private final Go5ProvjeraKapaciteta step = new Go5ProvjeraKapaciteta();

    @Test
    void rejectsWhenBedsExceedMax() {
        assertThat(step.check(ctx(6, 10, 4, 10)))
                .isInstanceOf(ValidationResult.Rejected.class);
    }

    @Test
    void rejectsWhenGuestsExceedMax() {
        assertThat(step.check(ctx(4, 12, 4, 10)))
                .isInstanceOf(ValidationResult.Rejected.class);
    }

    @Test
    void acceptsWhenWithinLimits() {
        assertThat(step.check(ctx(4, 8, 4, 10)))
                .isInstanceOf(ValidationResult.Passed.class);
    }

    @Test
    void passes_whenCoreNull() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 100, 200, true, true, true);
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        ValidationContext k = new ValidationContext(acc, lessor, null);
        assertThat(step.check(k)).isInstanceOf(ValidationResult.Passed.class);
    }

    private ValidationContext ctx(int beds, int guests, int maxBeds, int maxGuests) {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", beds, guests, true, true, true);
        CoreObjektEntity core = GoTestFixtures.coreObject(maxBeds, maxGuests, true);
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        return new ValidationContext(acc, lessor, core);
    }
}
