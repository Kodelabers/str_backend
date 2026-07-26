package com.str.backend.egop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EgopNazivTest {

    @Test
    void normalize_stripsDiacriticsTrimAndCase() {
        assertEquals("fizicka osoba", EgopNaziv.normalize("  Fizička Osoba "));
        assertEquals("izdavanje registracijskog broja", EgopNaziv.normalize("Izdavanje Registracijskog broja"));
        assertNull(EgopNaziv.normalize(null));
    }

    @Test
    void resolveId_matchesDiacriticInsensitively() {
        Map<String, String> codebook = Map.of("Fizička osoba", "3", "Pravna osoba", "2");

        assertEquals("3", EgopNaziv.resolveId(codebook, "FIZICKA OSOBA"));
        assertEquals("2", EgopNaziv.resolveId(codebook, " pravna osoba "));
    }

    @Test
    void resolveId_returnsNullWhenMissing() {
        assertNull(EgopNaziv.resolveId(Map.of("Nešto", "1"), "Ne postoji"));
        assertNull(EgopNaziv.resolveId(null, "Bilo što"));
    }
}
