package com.str.backend.kp;

import com.str.backend.document.StrDocumentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Konfiguracija izravne dostave u korisnički pretinac (KP) preko APIS IT „Sustava za zaprimanje
 * poruka" (SZP), tehnička specifikacija KP 3.6. Vidi {@code docs/KP-INTEGRACIJA.md}.
 *
 * <p>Tri skupine vrijednosti, i nijedna nema ugrađeni default jer bi pogrešna tiho slala u tuđi
 * registar ili s pogrešnim pravnim učinkom:
 * <ul>
 *   <li><b>dodjeljuje APIS/REGUP</b> pri registraciji: {@code sender-id}, {@code id-posiljatelja-fo},
 *       {@code id-posiljatelja-ps}, {@code tip-poruke-fo/ps.<vrsta>};</li>
 *   <li><b>prijavljuje MINT</b> u obrascu za e-poruku, a poruka mu mora odgovarati (Z007/K015,
 *       Z002/K013): pravni učinak, suglasnost, potvrda primitka, e-potpis potvrde, rok, čuvanje,
 *       razina sigurnosti;</li>
 *   <li><b>potvrđuje APIS</b> (primjeri XML-a nisu stigli s paketom): {@code data-encoding},
 *       {@code content-mime-type}, {@code izvor-registar-oib}.</li>
 * </ul>
 *
 * <p>Kao i kod {@link com.str.backend.egop.EgopProperties}, vrijednosti su prazne dok je KP
 * ugašen, pa se ne validiraju bean-validationom; {@link #requireComplete()} zove {@link KpConfig},
 * koji se diže samo uz uključen KP.
 */
@ConfigurationProperties("str.kp")
public record KpProperties(
        boolean enabled,
        String url,
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        String keystorePath,
        String keystorePassword,
        String keyAlias,
        String truststorePath,
        String truststorePassword,
        String senderId,
        String idPosiljateljaFo,
        String idPosiljateljaPs,
        String izvorRegistarOib,
        String dataEncoding,
        String contentMimeType,
        Integer razinaSigurnosti,
        Boolean pravniUcinak,
        Boolean potrebnaSuglasnost,
        Boolean potrebnaPotvrda,
        Boolean potrebanDsig,
        Integer rokDani,
        Boolean neradniDanURok,
        Boolean trajnoCuvanje,
        Integer godineCuvanja,
        Map<String, Integer> tipPorukeFo,
        Map<String, Integer> tipPorukePs,
        Map<String, String> naslov,
        Duration maxAktAge
) {

    /** ServiceId usluga SZP-a (tehnička spec. 3.6, pogl. 4 i 5). */
    public static final String SERVICE_SLANJE_FO = "123";
    public static final String SERVICE_SLANJE_PS = "125";
    public static final String SERVICE_AKTIVNOST_PS = "128";
    public static final String SERVICE_AKTIVNOST_FO = "129";

    /** Hrvatski primatelj — FO {@code OznakaDrzave}, PS {@code Drzava}. */
    public static final String DRZAVA_HR = "HR";

    public enum DataEncoding { EMBEDDED, BASE64 }

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    /** Slanje ide asinkrono (KpExecutor), ali s ograničenim poolom — obješen poziv ga blokira. */
    private static final int DEFAULT_READ_TIMEOUT_MS = 20_000;
    private static final Duration DEFAULT_MAX_AKT_AGE = Duration.ofDays(7);

    /** Dopuštene kombinacije suglasnost/potvrda/e-potpis za poruku s pravnim učinkom (Z019). */
    private static final Set<String> PSPU_KOMBINACIJE = Set.of("FFF", "FTF", "FTT", "TFF", "TTF", "TTT");

    /** Izlazna pismena — svako mora imati registriran tip poruke u oba pretinca. */
    static final List<StrDocumentType> IZLAZNE_VRSTE = Arrays.stream(StrDocumentType.values())
            .filter(t -> t.smjer() == StrDocumentType.Smjer.IZLAZNO)
            .toList();

    public KpProperties {
        connectTimeoutMs = connectTimeoutMs == null ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs == null ? DEFAULT_READ_TIMEOUT_MS : readTimeoutMs;
        maxAktAge = maxAktAge == null ? DEFAULT_MAX_AKT_AGE : maxAktAge;
        tipPorukeFo = withoutEmpty(tipPorukeFo);
        tipPorukePs = withoutEmpty(tipPorukePs);
        naslov = withoutEmpty(naslov);
    }

    /**
     * Odbija nepotpunu ili nedopuštenu konfiguraciju na startu, svim greškama odjednom. Bez
     * ovoga bi svaka pogreška stigla tek kao šifra odbijanja na prvoj stvarnoj poruci stranci.
     */
    public void requireComplete() {
        List<String> errors = new ArrayList<>();
        require(errors, url, "url");
        require(errors, keystorePath, "keystore-path");
        require(errors, keystorePassword, "keystore-password");
        requireDigits(errors, senderId, "sender-id");
        requireDigits(errors, idPosiljateljaFo, "id-posiljatelja-fo");
        requireDigits(errors, idPosiljateljaPs, "id-posiljatelja-ps");
        if (izvorRegistarOib == null || !izvorRegistarOib.strip().matches("[0-9]{1,3}")) {
            errors.add("izvor-registar-oib (oznaka registra za OIB pravne osobe; spec kaže 1, shema [0-9]{3})");
        }
        if (dataEncoding == null || Arrays.stream(DataEncoding.values())
                .noneMatch(e -> e.name().equalsIgnoreCase(dataEncoding.strip()))) {
            errors.add("data-encoding (EMBEDDED ili BASE64)");
        }
        require(errors, contentMimeType, "content-mime-type");
        if (razinaSigurnosti == null || razinaSigurnosti < 2 || razinaSigurnosti > 4) {
            errors.add("razina-sigurnosti (2 niska, 3 značajna, 4 visoka)");
        }
        if (pravniUcinak == null) {
            errors.add("pravni-ucinak");
        } else if (pravniUcinak) {
            requirePravniUcinak(errors);
        }
        for (StrDocumentType vrsta : IZLAZNE_VRSTE) {
            requireTip(errors, tipPorukeFo, "tip-poruke-fo." + vrsta.slug(), vrsta);
            requireTip(errors, tipPorukePs, "tip-poruke-ps." + vrsta.slug(), vrsta);
            if (naslov(vrsta).length() > 200) {
                errors.add("naslov." + vrsta.slug() + " (najviše 200 znakova)");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("str.kp.enabled=true, ali konfiguracija KP-a nije potpuna"
                    + " ili nije dopuštena: " + String.join(", ", errors));
        }
    }

    /** Pravila za poruku s pravnim učinkom: Z019, Z023, K016–K018, K027. */
    private void requirePravniUcinak(List<String> errors) {
        if (potrebnaSuglasnost == null || potrebnaPotvrda == null || potrebanDsig == null) {
            errors.add("potrebna-suglasnost, potrebna-potvrda i potreban-dsig (obavezni uz pravni-ucinak=true)");
        } else if (!PSPU_KOMBINACIJE.contains(tf(potrebnaSuglasnost) + tf(potrebnaPotvrda) + tf(potrebanDsig))) {
            errors.add("kombinacija suglasnost/potvrda/e-potpis " + tf(potrebnaSuglasnost) + "/"
                    + tf(potrebnaPotvrda) + "/" + tf(potrebanDsig) + " nije dopuštena (Z019)");
        } else if (traziRok()) {
            if (rokDani == null || rokDani <= 0) {
                errors.add("rok-dani (pozitivan broj dana uz suglasnost ili potvrdu primitka; Z017)");
            }
            if (neradniDanURok == null) {
                errors.add("neradni-dan-u-rok (obavezan uz suglasnost ili potvrdu primitka)");
            }
        } else if ((rokDani != null && rokDani != 0) || Boolean.TRUE.equals(neradniDanURok)) {
            errors.add("rok-dani=0 i neradni-dan-u-rok=false za poruku bez suglasnosti i potvrde (Z023)");
        }
        if (trajnoCuvanje == null) {
            errors.add("trajno-cuvanje (obavezno uz pravni-ucinak=true)");
        } else if (!trajnoCuvanje && (godineCuvanja == null || godineCuvanja < 1 || godineCuvanja > 99)) {
            errors.add("godine-cuvanja (1–99, obavezno uz trajno-cuvanje=false)");
        }
    }

    /** Traži li poruka rok: suglasnost, potvrda ili e-potpis potvrde (RokDani i NeradniDanURok). */
    public boolean traziRok() {
        return Boolean.TRUE.equals(potrebnaSuglasnost) || Boolean.TRUE.equals(potrebnaPotvrda)
                || Boolean.TRUE.equals(potrebanDsig);
    }

    public boolean isPravniUcinak() {
        return Boolean.TRUE.equals(pravniUcinak);
    }

    public DataEncoding encoding() {
        return DataEncoding.valueOf(dataEncoding.strip().toUpperCase(java.util.Locale.ROOT));
    }

    /** {@code TipPoruke} (FO) odnosno {@code IdPorukeURegistru} (PS) registrirane e-poruke. */
    public int tipPoruke(KpPrimatelj.Tip tip, StrDocumentType vrsta) {
        Integer value = (tip == KpPrimatelj.Tip.FO ? tipPorukeFo : tipPorukePs).get(vrsta.slug());
        if (value == null) {
            throw new IllegalStateException("Nema str.kp.tip-poruke-" + tip.name().toLowerCase(java.util.Locale.ROOT)
                    + "." + vrsta.slug());
        }
        return value;
    }

    /**
     * Naslov poruke mora biti jednak naslovu registrirane e-poruke (poslovna spec. 3.1). Default
     * je naziv vrste pismena — taj popis predlažemo MINT-u za registraciju.
     */
    public String naslov(StrDocumentType vrsta) {
        String override = naslov.get(vrsta.slug());
        return override == null || override.isBlank() ? vrsta.vrstaPismenaNaziv() : override.strip();
    }

    /**
     * {@code application.properties} sve ključeve definira s praznim env defaultom, pa vezanje
     * daje {@code null} vrijednosti — one znače „nije zadano", ne grešku vezanja.
     */
    private static <V> Map<String, V> withoutEmpty(Map<String, V> map) {
        if (map == null) {
            return Map.of();
        }
        Map<String, V> copy = new java.util.HashMap<>();
        map.forEach((k, v) -> {
            if (v != null && !(v instanceof String str && str.isBlank())) {
                copy.put(k, v);
            }
        });
        return Map.copyOf(copy);
    }

    static String tf(Boolean value) {
        return Boolean.TRUE.equals(value) ? "T" : "F";
    }

    private static void requireTip(List<String> errors, Map<String, Integer> map, String key, StrDocumentType vrsta) {
        Integer value = map.get(vrsta.slug());
        if (value == null || value < 0) {
            errors.add(key);
        }
    }

    private static void require(List<String> errors, String value, String key) {
        if (value == null || value.isBlank()) {
            errors.add(key);
        }
    }

    private static void requireDigits(List<String> errors, String value, String key) {
        if (value == null || !value.strip().matches("[0-9]{1,9}")) {
            errors.add(key + " (1–9 znamenki)");
        }
    }
}
