package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registries.MpgiClient;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Go2TipGradevineTest {

    private final MpgiClient mpgiClient = mock(MpgiClient.class);
    private final Go2TipGradevine step = new Go2TipGradevine(mpgiClient);

    @Test
    void flagsContext_whenUnitsExceedThreshold() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        ValidationContext ctx = ctx(acc);
        when(mpgiClient.brojStambenihJedinica("Ulica 1, Zagreb")).thenReturn(10);

        ValidationResult r = step.check(ctx);

        assertThat(r).isInstanceOf(ValidationResult.Passed.class);
        assertThat(ctx.requiresCoOwnerConsent()).isTrue();
    }

    @Test
    void doesNotFlag_whenUnitsAtOrBelowThreshold() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        ValidationContext ctx = ctx(acc);
        when(mpgiClient.brojStambenihJedinica(anyString())).thenReturn(3);

        step.check(ctx);

        assertThat(ctx.requiresCoOwnerConsent()).isFalse();
    }

    @Test
    void skipsMpgi_whenNotBuilding() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, false, false, true);
        ValidationContext ctx = ctx(acc);

        ValidationResult r = step.check(ctx);

        assertThat(r).isInstanceOf(ValidationResult.Passed.class);
        assertThat(ctx.requiresCoOwnerConsent()).isFalse();
        verify(mpgiClient, never()).brojStambenihJedinica(anyString());
    }

    @Test
    void skipsMpgi_whenBuildingButNotApartments() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, false, true);
        ValidationContext ctx = ctx(acc);

        step.check(ctx);

        verify(mpgiClient, never()).brojStambenihJedinica(anyString());
    }

    private ValidationContext ctx(AccommodationEntity acc) {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        return new ValidationContext(acc, lessor);
    }
}
