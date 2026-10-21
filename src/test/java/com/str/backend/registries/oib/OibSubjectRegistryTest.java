package com.str.backend.registries.oib;

import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.RegistrySubject;
import com.str.backend.lessor.SubjectDataSource;
import com.str.backend.str.StrSubjectRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Uključen OIB sustav ne smije biti gori od eTurizma: kad osobu ne zna ili ne odgovori, podatak
 * dolazi iz {@code str.subject*}, a izvor se vidi u {@link RegistrySubject#source()}.
 */
class OibSubjectRegistryTest {

    private static final String OIB = "12312312316";

    private final OibRegistryHttpClient client = mock(OibRegistryHttpClient.class);
    private final StrSubjectRegistry eturizam = mock(StrSubjectRegistry.class);
    private final OibSubjectRegistry registry = new OibSubjectRegistry(client, eturizam);

    private static RegistrySubject subject(SubjectDataSource source) {
        return new RegistrySubject(OIB, "PERO", "PERIĆ", null, "ULICA", "1", "ZAGREB", "10000",
                "ZAGREB", null, source);
    }

    @Test
    void foundInOibRegistry_eturizamNotAsked() {
        when(client.findPerson(OIB)).thenReturn(Optional.of(subject(SubjectDataSource.OIB_REGISTAR)));

        assertThat(registry.findByOib(OIB)).get()
                .extracting(RegistrySubject::source).isEqualTo(SubjectDataSource.OIB_REGISTAR);
        verifyNoInteractions(eturizam);
    }

    @Test
    void unknownInOibRegistry_fallsBackToEturizam() {
        when(client.findPerson(OIB)).thenReturn(Optional.empty());
        when(eturizam.findByOib(OIB)).thenReturn(Optional.of(subject(SubjectDataSource.STR_SUBJEKT)));

        assertThat(registry.findByOib(OIB)).get()
                .extracting(RegistrySubject::source).isEqualTo(SubjectDataSource.STR_SUBJEKT);
    }

    /** Npr. 302: servis nije prepoznao dijeljenu sesiju. */
    @Test
    void oibRegistryUnavailable_fallsBackToEturizam() {
        when(client.findPerson(OIB)).thenThrow(new ExternalRegistryException("OIB", "status 302"));
        when(eturizam.findByOib(OIB)).thenReturn(Optional.of(subject(SubjectDataSource.STR_SUBJEKT)));

        assertThat(registry.findByOib(OIB)).get()
                .extracting(RegistrySubject::source).isEqualTo(SubjectDataSource.STR_SUBJEKT);
    }

    @Test
    void unknownEverywhere_isEmpty() {
        when(client.findPerson(OIB)).thenReturn(Optional.empty());
        when(eturizam.findByOib(OIB)).thenReturn(Optional.empty());

        assertThat(registry.findByOib(OIB)).isEmpty();
    }
}
