package com.str.backend.str.verifikacija;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sadržaj obavijesti službeniku o zahtjevu za žurnu verifikaciju — tekst iz predloška i
 * {@code podaci} (jsonb). Čiste funkcije, bez pristupa bazi.
 *
 * <p><b>Naziv i mjesto objekta nisu naš podatak</b> (eTurizam ih čuva iz unosa iznajmljivača), a ne
 * znamo prikazuje li eTurizam {@code tekst} kao HTML. Zato se prije umetanja čiste
 * ({@link #ocisti}): bez kontrolnih i formatnih znakova (prijelomi reda, bidi prepisivanje,
 * nevidljivi znakovi), bez {@code < > & " ' `}, s jednim razmakom, i skraćeni na
 * {@value #MAX_DIO} znakova — RB i ID objekta tako nikad ne ispadnu iz teksta zbog skraćivanja.
 */
final class VerifikacijaObavijest {

    /** {@code str.korisnicka_obavijest.tekst} je {@code varchar(1000)}. */
    static final int MAX_TEKST = 1000;

    /** Najdulji naziv odnosno mjesto u tekstu. */
    static final int MAX_DIO = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern RAZMACI = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

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
     * {@code „Apartman Marija" (Makarska, ID 1448035)}. Naziv i mjesto su eTurizmovi
     * ({@code findOwnership} pri izdavanju RB-a), očišćeni ({@link #ocisti}); kad ih nema, ostaje samo
     * id, po kojem ga službenik nađe.
     */
    static String opisObjekta(String naziv, String mjesto, String facilityId) {
        String cistNaziv = ocisti(naziv, MAX_DIO);
        String cistoMjesto = ocisti(mjesto, MAX_DIO);
        List<String> uZagradi = new ArrayList<>(2);
        if (!isBlank(cistoMjesto)) {
            uZagradi.add(cistoMjesto);
        }
        uZagradi.add("ID " + ocisti(facilityId, MAX_DIO));
        String zagrada = "(" + String.join(", ", uZagradi) + ")";
        return isBlank(cistNaziv) ? zagrada : "„" + cistNaziv + "“ " + zagrada;
    }

    /**
     * Novi objekt (iz zahtjeva za RB, unos iznajmljivača, očišćen): naziv pod navodnicima i mjesto u
     * zagradi — npr. {@code „Apartman Ana" (Split)}. Bez naziva samo mjesto u zagradi, a bez oboje
     * „(bez naziva)", da rečenica ostane cijela; RB ga svejedno jednoznačno određuje.
     */
    static String opisNovogObjekta(String naziv, String mjesto) {
        String cistNaziv = ocisti(naziv, MAX_DIO);
        String cistoMjesto = ocisti(mjesto, MAX_DIO);
        String zagrada = isBlank(cistoMjesto) ? null : "(" + cistoMjesto + ")";
        if (isBlank(cistNaziv)) {
            return zagrada != null ? zagrada : "(bez naziva)";
        }
        String navodnici = "„" + cistNaziv + "“";
        return zagrada != null ? navodnici + " " + zagrada : navodnici;
    }

    /**
     * Tuđi tekst spreman za umetanje u obavijest: bez kontrolnih ({@code Cc}) i formatnih ({@code Cf},
     * npr. U+202E) znakova, bez znakova {@code < > & " ' `}, razmaci sažeti u jedan, skraćeno na
     * {@code max}. {@code null} ostaje {@code null}.
     */
    static String ocisti(String value, int max) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT) {
                out.append(' ');
            } else if ("<>&\"'`".indexOf(cp) < 0) {
                out.appendCodePoint(cp);
            }
        });
        String sazeto = RAZMACI.matcher(out).replaceAll(" ").strip();
        return skrati(sazeto, max);
    }

    /** {@code podaci}: identifikatori po kojima eTurizam može obavijest povezati sa zahtjevom. */
    static String podaci(String rn, long documentId, String facilityId, long verificationRequestId) {
        Map<String, Object> podaci = new LinkedHashMap<>();
        podaci.put("rn", rn);
        podaci.put("documentId", documentId);
        podaci.put("facilityId", numericOrText(facilityId));
        podaci.put("verificationRequestId", verificationRequestId);
        return json(podaci);
    }

    /**
     * {@code podaci} za novi objekt uz predano rješenje: zahtjeva ni dokumenta nema, pa obavijest
     * nosi RB i id rješenja u STR-u ({@code str_rn.categorization_decision}).
     */
    static String podaciNovogObjekta(String rn, UUID decisionId) {
        Map<String, Object> podaci = new LinkedHashMap<>();
        podaci.put("rn", rn);
        podaci.put("decisionId", decisionId == null ? null : decisionId.toString());
        return json(podaci);
    }

    private static String json(Map<String, Object> podaci) {
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
