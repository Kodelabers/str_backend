package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registries.MpgiClient;
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Zahtjev u ime tvrtke (e-Zastupanja): iznajmljivač je tvrtka bez poznate adrese sjedišta, pa GO
 * pipeline mora proći bez iznimke. GO-1 daje „nije domaćin", označeno kao „ne utvrđuje se" — to je
 * trenutno pravilo i čeka potvrdu naručitelja. GO-5 (kapacitet) o iznajmljivaču ne ovisi.
 */
class LegalEntityGoPipelineTest {

    private static LessorEntity company() {
        // Isto kao SubjectProfileService#toLegalLessor kad sjedište nije dohvaćeno: prazna adresa.
        LessorEntity lessor = LessorEntity.create("Pero", "Perić", "", "", "", "", null);
        lessor.setLessorOib("39986540678");
        lessor.applyNiasLegalEntity("ADRIATIQUE GROUP D.O.O.", null, "12312312316", "Pero Perić", null);
        return lessor;
    }

    @Test
    void go1_companyWithoutSeat_isNotHost_markedAsNotDetermined() {
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, false, false, true);

        ValidationResult r = new Go1HostStatus().check(new ValidationContext(acc, company()));

        assertThat(r).isInstanceOfSatisfying(ValidationResult.Passed.class,
                p -> assertThat(p.getDetail()).contains("pravna osoba"));
        assertThat(acc.getHost()).isFalse();
    }

    /** Strana pravna osoba iz non-EU registracije ima vlastitu županiju — za nju vrijedi dosadašnja usporedba. */
    @Test
    void go1_legalEntityWithCounty_keepsCountyComparison() {
        LessorEntity lessor = GoTestFixtures.lessor("Grad Zagreb", "Zagreb");
        lessor.applyLegalEntityOwner("Foreign Ltd", 1, "Wien", "FN123");
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, false, false, true);

        ValidationResult r = new Go1HostStatus().check(new ValidationContext(acc, lessor));

        assertThat(((ValidationResult.Passed) r).getDetail()).contains("county=true");
        assertThat(acc.getHost()).isTrue();
    }

    /** T8: sjedište iz OIB sustava daje županiju, pa GO-1 za tvrtku iz e-Zastupanja uspoređuje županije. */
    @Test
    void go1_companyWithSeatFromOibRegistry_comparesCounty() {
        LessorEntity lessor = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("39986540678");
        lessor.applyNiasLegalEntity("ADRIATIQUE GROUP D.O.O.", "080123456", "12312312316", "Pero Perić", null);
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, false, false, true);

        ValidationResult r = new Go1HostStatus().check(new ValidationContext(acc, lessor));

        assertThat(((ValidationResult.Passed) r).getDetail()).contains("county=true");
        assertThat(acc.getHost()).isTrue();
    }

    @Test
    void fullPipeline_forCompany_passesWithoutException() {
        MpgiClient mpgi = mock(MpgiClient.class);
        when(mpgi.brojStambenihJedinica(any())).thenReturn(2);
        ParallelValidationOrchestrator orchestrator = new ParallelValidationOrchestrator(List.of(
                new Go1HostStatus(), new Go2BuildingType(mpgi), new Go3LegalityCheck(),
                new Go4CoOwnerConsent(Clock.systemDefaultZone()), new Go5CapacityCheck()));
        AccommodationEntity acc = GoTestFixtures.accommodation("Grad Zagreb", "Zagreb", 2, 4, true, true, true);

        PipelineResult result = orchestrator.execute(new ValidationContext(acc, company()));

        assertThat(result.getOutcome()).isEqualTo(PipelineResult.Outcome.PASSED);
        assertThat(acc.getHost()).isFalse();
    }
}
