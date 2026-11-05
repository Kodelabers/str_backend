package com.str.backend.str.verifikacija;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sadržaj obavijesti službeniku o zahtjevu za žurnu verifikaciju — tekst iz predloška i
 * {@code podaci} (jsonb). Čiste funkcije, bez pristupa bazi.
 */
final class VerifikacijaObavijest {

    /** {@code str.korisnicka_obavijest.tekst} je {@code varchar(1000)}. */
    static final int MAX_TEKST = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private VerifikacijaObavijest() {
    }

    /**
     * Popunjava predložak: {@code {rn}} je RB, {@code {objekt}} opis objekta
     * ({@link #opisObjekta}). Rezultat je skraćen na {@value #MAX_TEKST} znakova.
     */
    static String tekst(String predlozak, String rn, String objekt) {
        return skrati(predlozak.replace("{rn}", rn).replace("{objekt}", objekt), MAX_TEKST);
    }

    /**
     * Naziv pod navodnicima, pa u zagradi mjesto i id u eTurizmu — npr.
     * {@code „Apartman Marija" (Makarska, ID 1448035)}. Naziv i mjesto su ono što je RB zahtjev
     * nakon dopune iz eTurizma imao; kad ih nema, ostaje samo id, po kojem ga službenik nađe.
     */
    static String opisObjekta(String naziv, String mjesto, String facilityId) {
        List<String> uZagradi = new ArrayList<>(2);
        if (!isBlank(mjesto)) {
            uZagradi.add(mjesto.strip());
        }
        uZagradi.add("ID " + facilityId);
        String zagrada = "(" + String.join(", ", uZagradi) + ")";
        return isBlank(naziv) ? zagrada : "„" + naziv.strip() + "“ " + zagrada;
    }

    /** {@code podaci}: identifikatori po kojima eTurizam može obavijest povezati sa zahtjevom. */
    static String podaci(String rn, long documentId, String facilityId, long verificationRequestId) {
        Map<String, Object> podaci = new LinkedHashMap<>();
        podaci.put("rn", rn);
        podaci.put("documentId", documentId);
        podaci.put("facilityId", numericOrText(facilityId));
        podaci.put("verificationRequestId", verificationRequestId);
        try {
            return JSON.writeValueAsString(podaci);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("podaci obavijesti se ne mogu serijalizirati", e);
        }
    }

    /**
     * Skraćuje na najviše {@code max} UTF-16 znakova, s „…" na kraju. Ne presijeca surogatni par,
     * pa je i broj znakova u PostgreSQL-u ({@code varchar(n)} broji kodne točke) unutar granice.
     */
    static String skrati(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    private static Object numericOrText(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "-".equals(value.strip());
    }
}
