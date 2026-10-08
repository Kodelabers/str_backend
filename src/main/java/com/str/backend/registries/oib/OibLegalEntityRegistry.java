package com.str.backend.registries.oib;

import com.str.backend.lessor.LegalEntityRegistry;
import com.str.backend.lessor.RegistryLegalEntity;
import com.str.backend.lessor.RegistrySubject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Tvrtka (sjedište, MBS) i njezin zastupnik (ime, adresa) iz OIB sustava — samo uz
 * {@code app.oib-registry.legal-entity-enabled=true}, neovisno o toku fizičke osobe.
 */
@Component
@ConditionalOnProperty(name = "app.oib-registry.legal-entity-enabled", havingValue = "true")
public class OibLegalEntityRegistry implements LegalEntityRegistry {

    private final OibRegistryHttpClient client;

    public OibLegalEntityRegistry(OibRegistryHttpClient client) {
        this.client = client;
    }

    @Override
    public Optional<RegistryLegalEntity> findLegalEntity(String oib) {
        return client.findLegalEntity(oib);
    }

    @Override
    public Optional<RegistrySubject> findPerson(String oib) {
        return client.findPerson(oib);
    }
}
