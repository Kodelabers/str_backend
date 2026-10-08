package com.str.backend.registries.oib;

import com.str.backend.lessor.RegistrySubject;
import com.str.backend.lessor.SubjectRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Fizička osoba u svoje ime iz OIB sustava — samo uz {@code app.oib-registry.enabled=true}. */
@Component
@ConditionalOnProperty(name = "app.oib-registry.enabled", havingValue = "true")
public class OibSubjectRegistry implements SubjectRegistry {

    private final OibRegistryHttpClient client;

    public OibSubjectRegistry(OibRegistryHttpClient client) {
        this.client = client;
    }

    @Override
    public Optional<RegistrySubject> findByOib(String oib) {
        return client.findPerson(oib);
    }
}
