package com.str.backend.auth.nias;

import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaClient;
import com.str.backend.registries.eovlastenja.ZastupanaTvrtka;
import com.str.backend.registries.eovlastenja.Zastupanje;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sesija je zajednička sa str-internal-api ({@code session.spring_session}): njihov servis učitava
 * sesiju koju je napisao STR, a naše klase nema. Atribut s klasom iz {@code com.str} njemu je
 * nečitljiv (na CDU-u 500 na OIB pretrazi), pa u sesiju smiju samo JDK i Spring tipovi. Provjerava
 * se serijalizirani oblik, isti koji Spring Session JDBC piše u tablicu.
 */
class SharedSessionAttributesTest {

    private static final String PERSON = "70000000004";
    private static final String COMPANY = "33333333360";
    private static final NiasIdentity IDENTITY = new NiasIdentity(PERSON, "Ana", "Horvat", "sesija-1", "TID1");

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    private final EOvlastenjaClient client = mock(EOvlastenjaClient.class);
    private final ActingSubjectService actingSubjects =
            new ActingSubjectService(client, new ActingSubjectRateLimiter(100, 100, clock), clock);
    private final MockHttpSession session = new MockHttpSession();

    @Test
    void niasFlow_leavesNoStrClassesInSession() throws Exception {
        when(client.verifyRepresentation(any(), any(), any())).thenReturn(new Zastupanje(PERSON, "ANA", "HORVAT",
                COMPANY, "TESTNA TVRTKA d.o.o.", List.of(new Zastupanje.Funkcija("034", "Direktor", "0"))));
        when(client.representedCompanies(any(), any())).thenReturn(List.of(new ZastupanaTvrtka(COMPANY, "TVRTKA")));

        new NiasSessionRegistry().register("name-id", "session-index", session);
        actingSubjects.representedCompanies(session, IDENTITY);
        actingSubjects.select(session, IDENTITY, COMPANY);

        assertNoStrClasses();
        assertThat(actingSubjects.current(session, PERSON)).isPresent();
    }

    @Test
    void unavailableOptions_leaveNoStrClassesInSession() throws Exception {
        when(client.representedCompanies(any(), any())).thenThrow(new ExternalRegistryException("EOVLASTENJA", "dolje"));

        try {
            actingSubjects.representedCompanies(session, IDENTITY);
        } catch (ActingSubjectOptionsUnavailableException expected) {
            // neuspjeh se pamti u sesiji — upravo taj zapis se provjerava
        }

        assertNoStrClasses();
    }

    private void assertNoStrClasses() throws Exception {
        assertThat(Collections.list(session.getAttributeNames())).isNotEmpty();
        for (String name : Collections.list(session.getAttributeNames())) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(session.getAttribute(name));
            }
            assertThat(bytes.toString(StandardCharsets.ISO_8859_1))
                    .as("atribut %s", name)
                    .doesNotContain("com.str.");
        }
    }
}
