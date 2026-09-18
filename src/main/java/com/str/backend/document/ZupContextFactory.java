package com.str.backend.document;

import com.str.backend.rn.dto.RnDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    private static final Locale HR = Locale.forLanguageTag("hr");

    /**
     * „10. rujna 2026." — mjesec u genitivu, kako ga piše predložak MINT-a. Genitiv daje CLDR
     * za {@code hr} u format-kontekstu ({@code MMMM}); {@code ZupContextFactoryTest} ga drži
     * za svih 12 mjeseci, jer bi promjena JDK-a tiho mogla vratiti nominativ („rujan").
     * Vodeća nula („09. rujna") je kao u predlošku naručitelja.
     */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd. MMMM yyyy.", HR);

    /** Vrijeme izdavanja u bloku e-pečata, u obliku kakav ima Porezna uprava. */
    private static final DateTimeFormatter VRIJEME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");
    private static final String ALGORITAM_DEFAULT = "SHA256withRSA";

    private final SecureRandom random = new SecureRandom();

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
        String naziv = required("tijelo.naziv", t.naziv());
        ctx.put("tijelo.naziv", naziv);
        // Zaglavlje i potpis pišu tijelo velikim slovima. Marker nekonfiguriranog naziva ostaje
        // kakav jest — ime propertyja velikim slovima više ne bi bilo ime propertyja.
        ctx.put("tijelo.nazivVelikim", (t.naziv() == null || t.naziv().isBlank())
                ? naziv : naziv.toUpperCase(HR));
        ctx.put("tijelo.oib", required("tijelo.oib", t.oib()));
        ctx.put("tijelo.adresa", optional(t.adresa()));
        ctx.put("tijelo.mjesto", optional(t.mjesto()));
        ctx.put("tijelo.ustrojstvenaJedinica", optional(t.ustrojstvenaJedinica()));
        ctx.put("tijelo.propisNadleznosti",
                required("tijelo.propis-nadleznosti", t.propisNadleznosti()));

        // Potpis je naziv tijela; ime službene osobe ispisuje se ispod njega samo ako je
        // konfigurirano (predložak MINT-a ga nema). Prazan redak renderer izbaci.
        ctx.put("potpisnik.ime", optional(properties.potpisnik().ime()));
        ctx.put("potpisnik.funkcija", optional(properties.potpisnik().funkcija()));

        ctx.put("akt.naslov", type.naslov());
        ctx.put("akt.klasa", optional(filing.klasa()));
        ctx.put("akt.urbroj", optional(filing.urBroj()));
        String datumAkta = datumAkta(type, d).format(DATE);
        ctx.put("akt.datum", datumAkta);
        // Gotovi redci zaglavlja: prije urudžbiranja KLASA i URBROJ ne postoje, a „KLASA:" bez
        // vrijednosti nije prazan redak pa ga renderer ne bi izbacio.
        ctx.put("akt.klasaRedak", prefiks("KLASA: ", filing.klasa()));
        ctx.put("akt.urbrojRedak", prefiks("URBROJ: ", filing.urBroj()));
        ctx.put("akt.mjestoDatum", join(", ", optional(t.mjesto()), datumAkta));
        // eGOP-ov JOP u desnom kutu zaglavlja, kao „P/21748084" na uredskom predlošku.
        ctx.put("akt.jopRedak", filing.jop() == null ? "" : "P/" + filing.jop());

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
        putAdresaRedak(ctx);

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
        ctx.put("objekt.skupina", skupina(d.rn()));
        ctx.put("objekt.kapacitet", d.maxBeds() == null ? "" : String.valueOf(d.maxBeds()));

        ctx.put("rok.ispravak", d.suspensionDeadline() == null
                ? labels.get("rok.default")
                : labels.format("rok.doDatuma", d.suspensionDeadline().format(DATE)));

        ctx.put("uputa.tekst", properties.uputaZa(type));

        // Pečat je pečat tijela: podnesak stranke (prigovor) ga ne dobiva, pa mu se podaci ni ne
        // grade — inače bi se za svaki takav render generirao broj zapisa i, uz nekonfiguriran
        // certifikat, upisao ERROR za blok koji se nikad ne ispisuje.
        if (type.smjer() == StrDocumentType.Smjer.IZLAZNO) {
            putEpecat(ctx, naziv);
        }

        return ctx;
    }

    /**
     * Datum akta. Obavijest o dodjeli renderira se i na zahtjev, mjesecima nakon izdavanja
     * ({@code GET /api/rn/{rn}/documents/dodjela}), pa mora nositi datum izdavanja RB-a — inače
     * ista obavijest svaki dan izlazi s novim datumom, različitim od verzije urudžbirane u
     * eGOP-u. Akti životnog ciklusa nastaju u trenutku prijelaza i PDF im se sprema, pa im je
     * datum rendera i datum akta.
     */
    private static LocalDate datumAkta(StrDocumentType type, RnDetailDto d) {
        if (type == StrDocumentType.DODJELA && d.issueDate() != null) {
            return d.issueDate();
        }
        return LocalDate.now();
    }

    /**
     * Skupina objekta iz samog registracijskog broja: {@code HR} + županija(2) + <b>skupina(2)</b>
     * + vrsta(2) + 12 (vidi {@link com.str.backend.domain.RegistrationNumber}). Danas se izdaje
     * samo skupina {@code 00} (domaćinstvo), ali natpis se ne smije hardkodirati — akt bi inače
     * tiho tvrdio domaćinstvo i za RB druge skupine. Nepoznata skupina daje vidljivu oznaku i
     * ERROR u logu, kao nekonfigurirani property.
     */
    private String skupina(String rn) {
        if (rn == null || rn.length() < 6) {
            return labels.get("vrijednost.nepoznata");
        }
        String kod = rn.substring(4, 6);
        String natpis = labels.find("objekt.skupina." + kod);
        if (natpis != null) {
            return natpis;
        }
        if (reportedMissing.add("objekt.skupina." + kod)) {
            log.error("document_unknown_group rn={} skupina={} — natpis nije u labels.properties;"
                    + " akt će nositi vidljivu oznaku", rn, kod);
        }
        return "[nepoznata skupina: " + kod + "]";
    }

    /**
     * Podaci za blok e-pečata — samo kad je pečat uključen, jer ih renderer bez njega ne čita,
     * a nekonfigurirani certifikat ne smije puniti log ERROR-ima dok pečata ionako nema.
     *
     * <p>Broj zapisa i kontrolni broj se zasad samo generiraju. Da bi portal iz
     * {@code urlProvjere} mogao prikazati izvornik, par se mora trajno spremiti uz PDF — to
     * pripada fazi pečatiranja, a do nje je {@code enabled=false} u svim okruženjima.
     */
    private void putEpecat(Map<String, String> ctx, String nazivTijela) {
        DocumentProperties.Epecat e = properties.epecat();
        if (!e.enabled()) {
            return;
        }
        String brojZapisa = UUID.randomUUID().toString();
        String kontrolniBroj = String.format("%08d", random.nextInt(100_000_000));
        String url = required("epecat.urlProvjere", e.urlProvjere());

        ctx.put("epecat.vrijemeIzdavanja", LocalDateTime.now(ZAGREB).format(VRIJEME));
        ctx.put("epecat.izdavateljCertifikata",
                required("epecat.izdavateljCertifikata", e.izdavateljCertifikata()));
        ctx.put("epecat.nazivCertifikata",
                required("epecat.nazivCertifikata", e.nazivCertifikata()));
        ctx.put("epecat.algoritam", (e.algoritam() == null || e.algoritam().isBlank())
                ? ALGORITAM_DEFAULT : e.algoritam().strip());
        ctx.put("epecat.brojZapisa", brojZapisa);
        ctx.put("epecat.kontrolniBroj", kontrolniBroj);
        // QR samo kad URL portala stvarno postoji. Inače bi u kodu završila oznaka
        // „[nije konfigurirano: …]" — skenirajući kod koji vodi nikamo gori je od nikakvog,
        // a oznaka i dalje stoji vidljivo u retku „Na internet adresi …".
        if (e.urlProvjere() != null && !e.urlProvjere().isBlank()) {
            ctx.put("epecat.qr", url + (url.contains("?") ? "&" : "?")
                    + "zapis=" + brojZapisa + "&kb=" + kontrolniBroj);
        }
        ctx.put("epecat.provjera", labels.format("epecat.provjera", url));
        ctx.put("epecat.potvrda", labels.format("epecat.potvrda", nazivTijela));
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
        putAdresaRedak(ctx);
    }

    /**
     * Gotov redak „Adresa: …" — bez njega predložak mora sam pisati natpis pa uz nepoznatu
     * adresu na podnesku ostane goli „Adresa: ,". Isti razlog kao kod {@code akt.klasaRedak}:
     * redak s fiksnim natpisom renderer ne može izbaciti, gotov redak može.
     */
    private static void putAdresaRedak(Map<String, String> ctx) {
        String mjesto = join(" ", ctx.get("stranka.postanskiBroj"), ctx.get("stranka.mjesto"));
        ctx.put("stranka.adresaRedak",
                prefiks("Adresa: ", join(", ", ctx.get("stranka.adresa"), mjesto)));
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
