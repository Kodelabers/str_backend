package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class Go4SuglasnostSuvlasnikaTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private final Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private final Go4SuglasnostSuvlasnika step = new Go4SuglasnostSuvlasnika(clock);

    @Test
    void skips_whenGo2DidNotFlag() {
        AccommodationEntity acc = GoTestFixtures.accommodationWithConsent(null, null, null);
        ValidationContext ctx = ctx(acc, false);
        assertThat(step.check(ctx)).isInstanceOf(ValidationResult.Passed.class);
    }

    @Test
    void passes_whenFlaggedAndConsentValid() {
        AccommodationEntity acc = GoTestFixtures.accommodationWithConsent(true, TODAY.minusDays(10), TODAY.plusDays(30));
        ValidationContext ctx = ctx(acc, true);
        assertThat(step.check(ctx)).isInstanceOf(ValidationResult.Passed.class);
    }

    @Test
    void passes_whenFlaggedAndConsentWithoutExpiry() {
        AccommodationEntity acc = GoTestFixtures.accommodationWithConsent(true, TODAY.minusDays(10), null);
        ValidationContext ctx = ctx(acc, true);
        assertThat(step.check(ctx)).isInstanceOf(ValidationResult.Passed.class);
    }

    @Test
    void rejects_whenFlaggedAndConsentMissing() {
        AccommodationEntity acc = GoTestFixtures.accommodationWithConsent(null, null, null);
        ValidationContext ctx = ctx(acc, true);
        assertThat(step.check(ctx)).isInstanceOf(ValidationResult.Rejected.class);
    }

    @Test
    void rejects_whenFlaggedAndConsentFalse() {
        AccommodationEntity acc = GoTestFixtures.accommodationWithConsent(false, TODAY.minusDays(10), null);
        ValidationContext ctx = ctx(acc, true);
        assertThat(step.check(ctx)).isInstanceOf(ValidationResult.Rejected.class);
    }

    @Test
    void rejects_whenWithdrawalDateIsToday() {
        AccommodationEntity acc = GoTestFixtures.accommodationWithConsent(true, TODAY.minusDays(30), TODAY);
        ValidationContext ctx = ctx(acc, true);
        assertThat(step.check(ctx)).isInstanceOf(ValidationResult.Rejected.class);
    }

    @Test
    void rejects_whenWithdrawalDateInPast() {
        AccommodationEntity acc = GoTestFixtures.accommodationWithConsent(true, TODAY.minusDays(30), TODAY.minusDays(1));
        ValidationContext ctx = ctx(acc, true);
        assertThat(step.check(ctx)).isInstanceOf(ValidationResult.Rejected.class);
    }

    private ValidationContext ctx(AccommodationEntity acc, boolean flagged) {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        ValidationContext k = new ValidationContext(acc, lessor, null);
        if (flagged) {
            k.markCoOwnerConsentRequired();
        }
        return k;
    }
}
