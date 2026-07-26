package com.str.backend.document;

import com.str.backend.rn.dto.RnDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gradi mapu placeholdera za jedan akt. Čista funkcija nad već dohvaćenim podacima — namjerno
 * bez repozitorija, da paket {@code document} ne ovisi o {@code request}/{@code egop} (koji
 * ovise o njemu).
 *
 * <p>Ključ koji nedostaje u mapi ruši render ({@link ZupPlaceholders}), pa ovdje svaki
 * dokumentirani placeholder mora dobiti vrijednost — makar praznu. Prazan string je
 * legitiman i renderer takav redak izbaci; nedostajući ključ znači tipfeler u predlošku.
 */
@Component
public class ZupContextFactory {

    private static final Logger log = LoggerFactory.getLogger(ZupContextFactory.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy.");

    private final DocumentProperties properties;
    private final DocumentLabels labels;

    /**
     * Da isti nedostajući property ne zatrpa log pri svakom renderu. Konkurentni skup jer je
     * ovo singleton bean, a renderi idu s više request threadova.
     */
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    public ZupContextFactory(DocumentProperties properties, DocumentLabels labels) {
        this.properties = properties;
        this.labels = labels;
    }

    /**
     * @param razlog razlog promjene statusa, već razriješen u tekst (slobodan tekst iz zahtjeva
     *               ili natpis okidača iz revizijskog traga); {@code null} → „razlog nije naveden"
     */
    public Map<String, String> forRn(StrDocumentType type, RnDetailDto d, String razlog,
                                     FilingReference filing) {
        Map<String, String> ctx = new HashMap<>();

        DocumentProperties.Tijelo t = properties.tijelo();
        ctx.put("tijelo.naziv", required("tijelo.naziv", t.naziv()));
        ctx.put("tijelo.oib", required("tijelo.oib", t.oib()));
        ctx.put("tijelo.adresa", optional(t.adresa()));
        ctx.put("tijelo.mjesto", optional(t.mjesto()));
        ctx.put("tijelo.ustrojstvenaJedinica", optional(t.ustrojstvenaJedinica()));
        ctx.put("tijelo.propisNadleznosti",
                required("tijelo.propis-nadleznosti", t.propisNadleznosti()));

        ctx.put("potpisnik.ime", required("potpisnik.ime", properties.potpisnik().ime()));
        ctx.put("potpisnik.funkcija",
                required("potpisnik.funkcija", properties.potpisnik().funkcija()));

        ctx.put("akt.naslov", type.naslov());
        ctx.put("akt.klasa", optional(filing.klasa()));
        ctx.put("akt.urbroj", optional(filing.urBroj()));
        String danas = LocalDate.now().format(DATE);
        ctx.put("akt.datum", danas);
        // Gotovi redci zaglavlja: prije urudžbiranja KLASA i URBROJ ne postoje, a „KLASA:" bez
        // vrijednosti nije prazan redak pa ga renderer ne bi izbacio.
        ctx.put("akt.klasaRedak", prefiks("KLASA: ", filing.klasa()));
        ctx.put("akt.urbrojRedak", prefiks("URBROJ: ", filing.urBroj()));
        ctx.put("akt.mjestoDatum", join(", ", optional(t.mjesto()), danas));

        ctx.put("stranka.naziv", strankaNaziv(d));
        ctx.put("stranka.oib", optional(d.lessorOib()));
        ctx.put("stranka.identifikator", identifikator(d.lessorOib()));
        ctx.put("stranka.zastupnik", zastupnik(d));
        // Adresa prebivališta nije na RnDetailDto (i namjerno se ne dodaje — taj DTO ide i na
        // /api/rn/{rn}). Puni je StrDocumentService kroz putStrankaAdresa; ovdje su defaulti.
        ctx.put("stranka.adresa", "");
        ctx.put("stranka.mjesto", optional(d.legalEntityCity()));
        // LessorEntity nema poštanski broj (poznata rupa, §15.2 eGOP analize). Prazan ključ je
        // namjeran: dostava ide u korisnički pretinac, ne poštom, pa akt time nije neispravan.
        ctx.put("stranka.postanskiBroj", "");
        ctx.put("stranka.email", optional(d.lessorEmail()));

        ctx.put("rn.broj", optional(d.rn()));
        ctx.put("rn.status", labels.status(d.status()));
        ctx.put("rn.datumIzdavanja", date(d.issueDate()));
        ctx.put("rn.razlog", (razlog == null || razlog.isBlank())
                ? labels.get("razlog.nijeNaveden") : razlog.strip());

        ctx.put("objekt.naziv", fallback(d.accommodationName()));
        ctx.put("objekt.adresa", objektAdresa(d));
        ctx.put("objekt.mjesto", optional(d.city()));
        ctx.put("objekt.zupanija", optional(d.county()));
        ctx.put("objekt.vrsta", fallback(d.accommodationTypeName()));
        ctx.put("objekt.kapacitet", d.maxBeds() == null ? "" : String.valueOf(d.maxBeds()));

        ctx.put("rok.ispravak", d.suspensionDeadline() == null
                ? labels.get("rok.default")
                : labels.format("rok.doDatuma", d.suspensionDeadline().format(DATE)));

        ctx.put("uputa.tekst", properties.uputaZa(type));

        return ctx;
    }

    /** Adresa iznajmljivača ne postoji na {@link RnDetailDto}; puni je pozivatelj kad je ima. */
    public static void putStrankaAdresa(Map<String, String> ctx, String ulica, String kucniBroj,
                                        String mjesto, String postanskiBroj) {
        ctx.put("stranka.adresa", join(" ", ulica, kucniBroj));
        if (mjesto != null && !mjesto.isBlank()) {
            ctx.put("stranka.mjesto", mjesto.strip());
        }
        if (postanskiBroj != null && !postanskiBroj.isBlank()) {
            ctx.put("stranka.postanskiBroj", postanskiBroj.strip());
        }
    }

    private String strankaNaziv(RnDetailDto d) {
        if (d.lessorLegalEntityName() != null && !d.lessorLegalEntityName().isBlank()) {
            return d.lessorLegalEntityName().strip();
        }
        String name = join(" ", d.lessorFirstName(), d.lessorLastName());
        return name.isEmpty() ? labels.get("vrijednost.nepoznata") : name;
    }

    /** Čl. 98. st. 2 traži OIB stranke „ako joj je dodijeljen" — non-EU iznajmljivač ga nema. */
    private String identifikator(String oib) {
        return (oib == null || oib.isBlank())
                ? labels.get("stranka.bezOiba")
                : labels.format("stranka.oibPrefiks", oib.strip());
    }

    private String zastupnik(RnDetailDto d) {
        String ime = d.legalRepresentativeName();
        if (ime == null || ime.isBlank()) {
            return "";
        }
        String oib = d.representativeOib();
        String s = (oib == null || oib.isBlank()) ? ime.strip()
                : ime.strip() + ", " + labels.format("stranka.oibPrefiks", oib.strip());
        return labels.format("stranka.zastupnikPredlozak", s);
    }

    private String objektAdresa(RnDetailDto d) {
        String ulica = join(" ", d.street(), d.streetNumber());
        String grad = optional(d.city());
        String spojeno = ulica.isEmpty() ? grad
                : (grad.isEmpty() ? ulica : ulica + ", " + grad);
        return spojeno.isEmpty() ? labels.get("vrijednost.nepoznata") : spojeno;
    }

    private String date(LocalDate value) {
        return value == null ? labels.get("vrijednost.nepoznata") : value.format(DATE);
    }

    private String fallback(String value) {
        return (value == null || value.isBlank()) ? labels.get("vrijednost.nepoznata") : value.strip();
    }

    private static String optional(String value) {
        return value == null ? "" : value.strip();
    }

    /** Prazna vrijednost ne smije ostaviti goli natpis („KLASA:") u zaglavlju. */
    private static String prefiks(String natpis, String value) {
        return (value == null || value.isBlank()) ? "" : natpis + value.strip();
    }

    private static String join(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(part.strip());
        }
        return sb.toString();
    }

    /**
     * Nekonfigurirani identitet tijela ne smije proći nezapaženo: umjesto praznine u akt ide
     * vidljiva oznaka, a u log jednom ide ERROR s imenom propertyja.
     */
    private String required(String key, String value) {
        if (value != null && !value.isBlank()) {
            return value.strip();
        }
        String property = "str.documents." + toKebab(key);
        if (reportedMissing.add(property)) {
            log.error("document_property_missing property={} — akt će nositi vidljivu oznaku"
                    + " umjesto vrijednosti; postaviti prije izdavanja akata strankama", property);
        }
        return "[nije konfigurirano: " + property + "]";
    }

    private static String toKebab(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
