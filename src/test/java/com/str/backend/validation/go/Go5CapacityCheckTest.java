package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Go5CapacityCheckTest {

    private final Go5CapacityCheck step = new Go5CapacityCheck();

    @Test
    void passes_always() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 4, 8, true, true, true);
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        assertThat(step.check(new ValidationContext(acc, lessor))).isInstanceOf(ValidationResult.Passed.class);
    }
}
