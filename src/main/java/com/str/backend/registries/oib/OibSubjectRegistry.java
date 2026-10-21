package com.str.backend.registries.oib;

import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.RegistrySubject;
import com.str.backend.lessor.SubjectRegistry;
import com.str.backend.str.StrSubjectRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fizička osoba u svoje ime iz OIB sustava — samo uz {@code app.oib-registry.enabled=true}.
 *
 * <p>Kad OIB sustav osobu ne zna ili nije dostupan (npr. ne prepozna dijeljenu sesiju pa vrati
 * 302), podatak se traži u eTurizmu ({@link StrSubjectRegistry}). Bez toga bi uključivanje OIB
 * sustava bilo gore od dosadašnjeg stanja: osoba koju {@code str.subject*} zna dobila bi ručni unos
 * adrese. Izvor se vidi u {@link RegistrySubject#source()}.
 *
 * <p>{@link Primary}: {@link StrSubjectRegistry} je uvijek bean (ovdje je zamjenski izvor), pa uz
 * uključen OIB sustav postoje dvije implementacije porta, a {@code SubjectProfileService} dobiva ovu.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.oib-registry.enabled", havingValue = "true")
public class OibSubjectRegistry implements SubjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(OibSubjectRegistry.class);

    private final OibRegistryHttpClient client;
    private final StrSubjectRegistry fallback;

    public OibSubjectRegistry(OibRegistryHttpClient client, StrSubjectRegistry fallback) {
        this.client = client;
        this.fallback = fallback;
    }

    @Override
    public Optional<RegistrySubject> findByOib(String oib) {
        Optional<RegistrySubject> found;
        try {
            found = client.findPerson(oib);
        } catch (ExternalRegistryException e) {
            log.warn("oib_registry_fallback reason=unavailable source=str_subject");
            return fallback.findByOib(oib);
        }
        if (found.isPresent()) {
            return found;
        }
        log.info("oib_registry_fallback reason=not_found source=str_subject");
        return fallback.findByOib(oib);
    }
}
