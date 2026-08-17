package com.str.backend.egop;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preslikavanje STR-ovih vrsta na eGOP šifre.
 *
 * <p>Postoje <b>dva puta</b> do šifre, i to namjerno:
 *
 * <ul>
 *   <li>{@link Sifre} — šifra se zadaje direktno i ide u SOAP poziv bez ikakvog dohvata iz
 *       MDM-a. To je glavni put. Šifrarnik vrsta predmeta na testu ima 2430 unosa i
 *       <b>duple nazive</b> („Usluge u domaćinstvu" pod 7765 i 8906, „Ostalo" pod tri šifre),
 *       pa razrješavanje po nazivu ondje bira prvi unos iz odgovora — dakle arbitrarno.
 *       Uz zadane šifre MDM prestaje biti na kritičnom putu urudžbiranja.</li>
 *   <li>{@link Nazivi} — naziv se razrješava kroz MDM šifrarnik ({@code EgopCodebooks}).
 *       Fallback za okruženja gdje su vrste unesene pod našim nazivima.</li>
 * </ul>
 *
 * <p>{@link Sifre#privremene()} označava da su zadane šifre <b>tuđe, posudbene</b> — koriste
 * se samo da se prohodnost integracije može testirati prije nego stignu prave šifre za STR.
 * Zapisuje se na svako pismeno ({@code egop_pismeno.egop_vrsta_privremena}), pa se nakon
 * dolaska pravih šifri jednim {@code SELECT}-om zna što treba stornirati i ponovno poslati.
 */
@ConfigurationProperties("str.egop.vrste")
public record EgopVrsteProperties(Sifre sifre, Nazivi nazivi) {

    /**
     * @param pismena         šifra po slugu {@code StrDocumentType} (npr. {@code suspenzija})
     * @param pismenaFallback šifra za svaku vrstu pismena koja nije razriješena drukčije;
     *                        prazno = nerazriješena vrsta je greška
     */
    public record Sifre(
            String predmet,
            Integer ustroj,
            String subjektFizicka,
            String subjektPravna,
            Map<String, String> pismena,
            String pismenaFallback,
            boolean privremene
    ) {
        public Sifre {
            pismena = normalizeKeys(pismena);
        }
    }

    /** Isto, ali vrijednosti su nazivi koje treba razriješiti kroz MDM šifrarnik. */
    public record Nazivi(
            String predmet,
            String ustroj,
            Map<String, String> pismena,
            String pismenaFallback
    ) {
        public Nazivi {
            pismena = normalizeKeys(pismena);
        }
    }

    public EgopVrsteProperties {
        sifre = sifre == null
                ? new Sifre(null, null, null, null, Map.of(), null, false)
                : sifre;
        nazivi = nazivi == null ? new Nazivi(null, null, Map.of(), null) : nazivi;
    }

    /**
     * Slugovi vrsta pismena imaju crticu ({@code obustava-suspenzije}), a Spring iz
     * environment varijable {@code ..._PISMENA_OBUSTAVA_SUSPENZIJE} veže ključ mape s
     * podvlakom. Bez ovog izjednačavanja override zadan kroz env varijablu tiho promaši.
     */
    private static Map<String, String> normalizeKeys(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> normalized.put(normalizeKey(key), value));
        return Map.copyOf(normalized);
    }

    static String normalizeKey(String key) {
        return key == null ? null : key.trim().toLowerCase().replace('_', '-');
    }
}
