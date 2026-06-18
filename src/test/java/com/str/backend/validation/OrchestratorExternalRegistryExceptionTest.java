package com.str.backend.validation;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestratorExternalRegistryExceptionTest {

    @Test
    void externalRegistryException_propagatesUnchanged_notTreatedAsValidationFailure() {
        ValidationCheck failingCheck = new ValidationCheck() {
            @Override public String step() { return "GO-X"; }
            @Override public int order() { return 1; }
            @Override public Set<String> dependsOn() { return Set.of(); }
            @Override public ValidationResult check(ValidationContext context) {
                throw new ExternalRegistryException("GO-X", "MPGI unavailable", null);
            }
        };

        ParallelValidationOrchestrator orchestrator =
                new ParallelValidationOrchestrator(List.of(failingCheck));
        ValidationContext context = new ValidationContext(accommodation(), lessor());

        assertThatThrownBy(() -> orchestrator.execute(context))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("MPGI unavailable");
    }

    @Test
    void externalRegistryException_fromSecondCheck_propagates_evenWhenFirstPassed() {
        ValidationCheck passing = new ValidationCheck() {
            @Override public String step() { return "GO-1"; }
            @Override public int order() { return 1; }
            @Override public Set<String> dependsOn() { return Set.of(); }
            @Override public ValidationResult check(ValidationContext context) {
                return new ValidationResult.Passed("GO-1", "ok");
            }
        };
        ValidationCheck failing = new ValidationCheck() {
            @Override public String step() { return "GO-2"; }
            @Override public int order() { return 2; }
            @Override public Set<String> dependsOn() { return Set.of("GO-1"); }
            @Override public ValidationResult check(ValidationContext context) {
                throw new ExternalRegistryException("GO-2", "DGU timeout", null);
            }
        };

        ParallelValidationOrchestrator orchestrator =
                new ParallelValidationOrchestrator(List.of(passing, failing));
        ValidationContext context = new ValidationContext(accommodation(), lessor());

        assertThatThrownBy(() -> orchestrator.execute(context))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("DGU timeout");
    }

    private AccommodationEntity accommodation() {
        return AccommodationEntity.create(
                UUID.randomUUID(), "Grad Zagreb", "Zagreb", "Ilica", "1",
                2, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, true);
    }

    private LessorEntity lessor() {
        return LessorEntity.create("Marko", "Maric", "Ilica", "1", "Zagreb", "Grad Zagreb", "m@example.com");
    }
}
