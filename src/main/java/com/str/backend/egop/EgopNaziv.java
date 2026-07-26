package com.str.backend.egop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.Map;

/**
 * Mapiranje naziva iz eGOP šifrarnika (npr. "Izdavanje Registracijskog broja")
 * na šifru. eGOP API prima šifre, a poslovni dogovori se rade po nazivima —
 * normalizacija (trim, dijakritici, lowercase) čini mapiranje otpornim na
 * razlike u unosu.
 */
public final class EgopNaziv {

    private static final Logger log = LoggerFactory.getLogger(EgopNaziv.class);

    private EgopNaziv() {
    }

    public static String normalize(String naziv) {
        if (naziv == null) {
            return null;
        }
        return Normalizer.normalize(naziv.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }

    public static <T> T resolveId(Map<String, T> nazivToId, String naziv) {
        String target = normalize(naziv);
        if (target == null || nazivToId == null) {
            log.warn("eGOP mapiranje po nazivu preskočeno - naziv='{}', šifrarnik prazan={}",
                    naziv, nazivToId == null);
            return null;
        }
        T id = nazivToId.entrySet().stream()
                .filter(entry -> target.equals(normalize(entry.getKey())))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (id == null) {
            log.warn("eGOP mapiranje po nazivu nije pronašlo podudaranje za naziv='{}' (normalizirano='{}')",
                    naziv, target);
        } else {
            log.info("eGOP mapiranje po nazivu: '{}' -> {}", naziv, id);
        }
        return id;
    }
}
