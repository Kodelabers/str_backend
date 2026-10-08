package com.str.backend.lessor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * OIB sustav za tvrtke nije uključen: sjedište, MBS i adresa zastupnika ostaju nepoznati, a forma
 * to kaže napomenom. Izdavanje RB-a radi i bez njih — tvrtka se tada sprema bez sjedišta.
 */
@Component
@Conditional(UnavailableLegalEntityRegistry.LegalEntityFlowDisabled.class)
public class UnavailableLegalEntityRegistry implements LegalEntityRegistry {

    /**
     * Sve osim {@code true} — i prazna env varijabla — znači „isključeno", zrcalno uvjetu
     * {@code registries.oib.OibLegalEntityRegistry}, pa je uvijek aktivna točno jedna implementacija.
     */
    static final class LegalEntityFlowDisabled extends NoneNestedConditions {

        LegalEntityFlowDisabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "app.oib-registry.legal-entity-enabled", havingValue = "true")
        static final class Enabled {
        }
    }

    @Override
    public Optional<RegistryLegalEntity> findLegalEntity(String oib) {
        return Optional.empty();
    }

    @Override
    public Optional<RegistrySubject> findPerson(String oib) {
        return Optional.empty();
    }
}
