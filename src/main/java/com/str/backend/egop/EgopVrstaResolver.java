package com.str.backend.egop;

import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.codebook.VrstaPoslovnihSubjekata;
import com.str.backend.egop.exception.EgopBadRequestException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Jedino mjesto koje STR-ovu vrstu (predmeta, pismena, subjekta, org. jedinice) pretvara u
 * šifru koja ide u eGOP poziv.
 *
 * <p>Prevodi se <b>tek u trenutku poziva</b>. U bazi ostaju naši kanonski nazivi
 * ({@code egop_pismeno.vrsta_pismena_naziv}), koji su i dio unique ključa
 * {@code uq_egop_pismeno_submission_vrsta_act} i ključ po kojem
 * {@link EgopPismenoRepository} filtrira — pa preslikavanje više naših vrsta na
 * <i>istu</i> eGOP šifru ne ruši ni jedno ni drugo. Naziv na pismenu
 * ({@code KreirajPismeno2.nazivPismena}) također ostaje naš, tako da je u urudžbenom
 * zapisniku vidljivo što je stvarno poslano čak i kad je šifra vrste posudbena.
 *
 * <p>Redoslijed razrješavanja za vrstu pismena:
 * <ol>
 *   <li>zadana šifra za slug ({@code str.egop.vrste.sifre.pismena.<slug>});</li>
 *   <li>zadani naziv za slug, razriješen kroz MDM;</li>
 *   <li>naš kanonski naziv iz {@link StrDocumentType}, razriješen kroz MDM;</li>
 *   <li>zadana fallback šifra, odnosno fallback naziv.</li>
 * </ol>
 * Nerazriješena vrsta je {@link EgopBadRequestException} — ista greška kao i prije, samo
 * sada nakon četiri pokušaja.
 */
@Component
public class EgopVrstaResolver {

    private static final Logger log = LoggerFactory.getLogger(EgopVrstaResolver.class);

    /** Naziv vrste predmeta koji se ispisuje u {@code nazivPredmeta}, neovisno od šifre. */
    private static final String PREDMET_NAZIV_FALLBACK = "Izdavanje Registracijskog broja";

    private final EgopClient egopClient;
    private final EgopVrsteProperties properties;

    public EgopVrstaResolver(EgopClient egopClient, EgopVrsteProperties properties) {
        this.egopClient = egopClient;
        this.properties = properties;
    }

    /**
     * Razriješena vrsta pismena.
     *
     * @param privremena šifra je posudbena (vidi {@code str.egop.vrste.sifre.privremene});
     *                   zapisuje se na pismeno da se zna što nakon dolaska pravih šifri
     *                   treba stornirati
     */
    public record Vrsta(String sifra, boolean privremena) {}

    /** Ispis efektivnog mapiranja pri startu — prvi živi test se inače čita naslijepo. */
    @PostConstruct
    void logMapping() {
        EgopVrsteProperties.Sifre sifre = properties.sifre();
        log.info("egop_vrste_config privremene={} predmet={} ustroj={} subjekt_fizicka={}"
                        + " subjekt_pravna={} pismena={} pismena_fallback={}",
                sifre.privremene(), sifre.predmet(), sifre.ustroj(), sifre.subjektFizicka(),
                sifre.subjektPravna(), sifre.pismena(), sifre.pismenaFallback());
        if (sifre.privremene()) {
            log.warn("egop_vrste_privremene — eGOP šifre vrsta su POSUDBENE (privremeno mapiranje"
                    + " radi testiranja prohodnosti). Akti se urudžbiraju pod tuđom vrstom;"
                    + " egop_pismeno.egop_vrsta_privremena bilježi koji su to.");
        }
    }

    /** Naziv predmeta koji ide u {@code nazivPredmeta} — naš, čitljiv, ne šifrin. */
    public String predmetNaziv() {
        String naziv = properties.nazivi().predmet();
        return naziv == null || naziv.isBlank() ? PREDMET_NAZIV_FALLBACK : naziv;
    }

    public String vrstaPredmeta() throws EgopBadRequestException {
        String sifra = properties.sifre().predmet();
        if (isSet(sifra)) {
            return sifra;
        }
        return resolveByNaziv(egopClient.getVrstePredmeta(), predmetNaziv(), "vrsta predmeta");
    }

    public Integer nadleznaOrgJedinica() throws EgopBadRequestException {
        Integer sifra = properties.sifre().ustroj();
        if (sifra != null) {
            return sifra;
        }
        return resolveByNaziv(egopClient.getUstroj(), properties.nazivi().ustroj(),
                "nadležna org. jedinica");
    }

    public String tipOsobe(boolean pravnaOsoba) throws EgopBadRequestException {
        String sifra = pravnaOsoba
                ? properties.sifre().subjektPravna()
                : properties.sifre().subjektFizicka();
        if (isSet(sifra)) {
            return sifra;
        }
        String naziv = pravnaOsoba
                ? VrstaPoslovnihSubjekata.PRAVNA_OSOBA.getNaziv()
                : VrstaPoslovnihSubjekata.FIZICKA_OSOBA.getNaziv();
        return resolveByNaziv(egopClient.getVrstePoslovnihSubjekata(), naziv,
                "vrsta poslovnog subjekta");
    }

    /**
     * @param kanonskiNaziv naš naziv vrste pismena ({@link StrDocumentType#vrstaPismenaNaziv()});
     *                      isti onaj koji je zapisan na {@code egop_pismeno}
     */
    public Vrsta vrstaPismena(String kanonskiNaziv) throws EgopBadRequestException {
        Optional<String> slug = StrDocumentType.fromVrstaPismenaNaziv(kanonskiNaziv)
                .map(StrDocumentType::slug)
                .map(EgopVrsteProperties::normalizeKey);

        String zadanaSifra = slug.map(properties.sifre().pismena()::get).orElse(null);
        if (isSet(zadanaSifra)) {
            // Prije dohvata šifrarnika: uz zadanu šifru MDM ne smije biti na kritičnom putu.
            return privremena(zadanaSifra, kanonskiNaziv, "zadana šifra za slug");
        }

        Map<String, String> codebook = egopClient.getVrstePismena();

        String zadaniNaziv = slug.map(properties.nazivi().pismena()::get).orElse(null);
        if (isSet(zadaniNaziv)) {
            String sifra = resolveByNaziv(codebook, zadaniNaziv, "vrsta pismena");
            return privremena(sifra, kanonskiNaziv, "zadani naziv '" + zadaniNaziv + "'");
        }

        String vlastita = EgopNaziv.resolveId(codebook, kanonskiNaziv);
        if (isSet(vlastita)) {
            // Naša vrsta postoji u eGOP šifrarniku — ništa posudbeno, ništa za storniranje.
            return new Vrsta(vlastita, false);
        }

        String fallbackSifra = properties.sifre().pismenaFallback();
        if (isSet(fallbackSifra)) {
            return privremena(fallbackSifra, kanonskiNaziv, "fallback šifra");
        }

        String fallbackNaziv = properties.nazivi().pismenaFallback();
        if (isSet(fallbackNaziv)) {
            String sifra = resolveByNaziv(codebook, fallbackNaziv, "vrsta pismena (fallback)");
            return privremena(sifra, kanonskiNaziv, "fallback naziv '" + fallbackNaziv + "'");
        }

        throw new EgopBadRequestException("eGOP šifrarnik nema unos za vrstu pismena '"
                + kanonskiNaziv + "', a nije zadana ni šifra ni fallback —"
                + " provjeriti str.egop.vrste.* i MDM");
    }

    private Vrsta privremena(String sifra, String kanonskiNaziv, String izvor) {
        boolean privremena = properties.sifre().privremene();
        if (privremena) {
            log.warn("egop_vrsta_privremena naziv='{}' sifra={} izvor={} — pismeno se urudžbira"
                    + " pod tuđom vrstom", kanonskiNaziv, sifra, izvor);
        } else {
            log.debug("egop_vrsta_mapirana naziv='{}' sifra={} izvor={}", kanonskiNaziv, sifra, izvor);
        }
        return new Vrsta(sifra, privremena);
    }

    private <T> T resolveByNaziv(Map<String, T> codebook, String naziv, String opis)
            throws EgopBadRequestException {
        if (naziv == null || naziv.isBlank()) {
            throw new EgopBadRequestException("Za " + opis + " nije zadana ni šifra ni naziv —"
                    + " provjeriti str.egop.vrste.*");
        }
        T id = EgopNaziv.resolveId(codebook, naziv);
        if (id == null) {
            throw new EgopBadRequestException("eGOP šifrarnik nema unos za " + opis + " '" + naziv
                    + "' — provjeriti konfiguraciju i MDM");
        }
        return id;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
