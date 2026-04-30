package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Go1StatusDomacinaTest {

    private final Go1StatusDomacina step = new Go1StatusDomacina();

    @Test
    void marksHostTrue_whenCountyMatchesAndNotBuilding() {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        // building=false → qualifies for host
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, false, false, true);
        ValidationContext ctx = new ValidationContext(acc, lessor, null);

        ValidationResult r = step.check(ctx);

        assertThat(r).isInstanceOf(ValidationResult.Passed.class);
        assertThat(acc.getHost()).isTrue();
    }

    @Test
    void marksHostFalse_whenCountyMatchesButObjectIsBuilding() {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        // building=true → disqualifies even if county matches (per ZAK-2.1 rule)
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        ValidationContext ctx = new ValidationContext(acc, lessor, null);

        step.check(ctx);

        assertThat(acc.getHost()).isFalse();
    }

    @Test
    void marksHostFalse_whenCountyDiffers() {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        AccommodationEntity acc = GoTestFixtures.accommodation("Splitsko-dalmatinska", "Split", 2, 4, false, false, true);
        ValidationContext ctx = new ValidationContext(acc, lessor, null);

        step.check(ctx);

        assertThat(acc.getHost()).isFalse();
    }

    @Test
    void matchIsCaseInsensitiveOnCountyOnly() {
        // Per ZAK-2.1: only county is compared, not place/city
        LessorEntity lessor = GoTestFixtures.lessor("splitsko-dalmatinska", "Omiš");
        AccommodationEntity acc = GoTestFixtures.accommodation("Splitsko-Dalmatinska", "Split", 2, 4, false, false, true);
        ValidationContext ctx = new ValidationContext(acc, lessor, null);

        step.check(ctx);

        assertThat(acc.getHost()).isTrue();
    }

    @Test
    void alwaysReturnsPassed_evenOnMismatch() {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        AccommodationEntity acc = GoTestFixtures.accommodation("Istarska", "Pula", 2, 4, true, true, true);
        ValidationContext ctx = new ValidationContext(acc, lessor, null);

        assertThat(step.check(ctx)).isInstanceOf(ValidationResult.Passed.class);
    }
}
