package com.str.backend.document;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Sedam vrsta pismena koje STR urudžbira u eGOP predmet, prema mailu InfoDoma od 22.07.2026.
 *
 * <p>{@link #vrstaPismenaNaziv()} je <b>točan ključ eGOP šifrarnika</b> — po njemu
 * {@code EgopCodebooks} razrješava šifru. Ta su imena ranije živjela na tri mjesta
 * ({@code application.properties}, {@code EgopFilingService} i {@code EgopClientMock}); ovaj
 * enum je od sada jedini izvor.
 *
 * <p>{@link #requiredSections()} je <b>pravni minimum</b> koji {@link ZupTemplateLoader}
 * provjerava na startu. Sadržaj sekcije određuje predložak; ovaj popis samo brani da akt
 * ostane bez sekcije koju ZUP traži — npr. rješenje bez upute o pravnom lijeku, što po čl. 111.
 * ide na štetu tijela, ne stranke.
 */
public enum StrDocumentType {

    /**
     * Podnesak stranke po čl. 71. ZUP-a, ne akt po čl. 98. Renderira ga
     * {@code SubmissionPdfGenerator} kao tablični obrazac, pa nema predloška.
     */
    ZAHTJEV("zahtjev", "Zahtjev za registracijski broj", Smjer.ULAZNO,
            "ZAHTJEV ZA REGISTRACIJSKI BROJ", false, EnumSet.noneOf(ZupSection.class)),

    /**
     * Potvrda o činjenici iz službene evidencije (čl. 159.) — nema obrazloženja ni upute
     * o pravnom lijeku jer ništa ne dira u prava stranke.
     */
    DODJELA("dodjela", "Obavijest o dodjeli registracijskog broja", Smjer.IZLAZNO,
            "OBAVIJEST O DODJELI REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.NASLOV, ZupSection.UVOD, ZupSection.IZREKA,
                    ZupSection.DOSTAVNA_LISTA)),

    /** Opoziv na zahtjev samog iznajmljivača — evidencijska obavijest, bez pravnog lijeka. */
    OPOZIV("opoziv", "Obavijest o opozivu registracijskog broja", Smjer.IZLAZNO,
            "OBAVIJEST O OPOZIVU REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.NASLOV, ZupSection.UVOD, ZupSection.IZREKA,
                    ZupSection.DOSTAVNA_LISTA)),

    /**
     * Poziv na izjašnjavanje prije nepovoljne odluke (čl. 30. st. 2). Nije rješenje, pa nema
     * upute o pravnom lijeku — ali <b>mora</b> nositi rok za ispravak, koji po čl. 98. st. 3
     * ide u izreku.
     */
    PRIJEDLOG_SUSPENZIJE("prijedlog-suspenzije",
            "Obavijest o prijedlogu suspenzije registracijskog broja", Smjer.IZLAZNO,
            "OBAVIJEST O PRIJEDLOGU SUSPENZIJE REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.NASLOV, ZupSection.UVOD, ZupSection.IZREKA,
                    ZupSection.OBRAZLOZENJE, ZupSection.DOSTAVNA_LISTA)),

    /** Odluka koja dira u prava stranke → puna struktura čl. 98., uključujući uputu. */
    SUSPENZIJA("suspenzija", "Obavijest o suspenziji registracijskog broja", Smjer.IZLAZNO,
            "OBAVIJEST O SUSPENZIJI REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.NASLOV, ZupSection.UVOD, ZupSection.IZREKA,
                    ZupSection.OBRAZLOZENJE, ZupSection.UPUTA_O_PRAVNOM_LIJEKU,
                    ZupSection.DOSTAVNA_LISTA)),

    /** Kao suspenzija, ali trajno (WITHDRAWN je terminalan) — uputa je time još važnija. */
    POVLACENJE("povlacenje", "Obavijest o povlačenju registracijskog broja", Smjer.IZLAZNO,
            "OBAVIJEST O POVLAČENJU REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.NASLOV, ZupSection.UVOD, ZupSection.IZREKA,
                    ZupSection.OBRAZLOZENJE, ZupSection.UPUTA_O_PRAVNOM_LIJEKU,
                    ZupSection.DOSTAVNA_LISTA)),

    /**
     * Podnesak stranke (čl. 71. + čl. 122.) — nema izreke ni upute; tijelo prigovora ide u
     * obrazloženje, jer čl. 108. st. 1 traži da stranka navede zbog čega je nezadovoljna.
     */
    PRIGOVOR("prigovor", "Prigovor na prijedlog suspenzije", Smjer.ULAZNO,
            "PRIGOVOR NA PRIJEDLOG SUSPENZIJE", true,
            EnumSet.of(ZupSection.NASLOV, ZupSection.UVOD, ZupSection.OBRAZLOZENJE)),

    /**
     * Ukidanje suspenzije — stranci u korist, pa nema obrazloženja ni upute.
     *
     * <p><b>Nije među 7 vrsta pismena iz InfoDomovog maila</b> i nema šifru u eGOP šifrarniku,
     * iako je Knjiga testiranja spominje (TC-STR-2.2-001). Predložak i okidač postoje, a
     * urudžbiranje je iza {@code str.egop.urudzbiraj-reaktivaciju} (ugašeno) dok InfoDom ne
     * potvrdi šifru — s ugašenom zastavicom iznajmljivač i dalje dobiva obavijest e-poštom.
     */
    REAKTIVACIJA("reaktivacija", "Obavijest o reaktivaciji registracijskog broja", Smjer.IZLAZNO,
            "OBAVIJEST O REAKTIVACIJI REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.NASLOV, ZupSection.UVOD, ZupSection.IZREKA,
                    ZupSection.DOSTAVNA_LISTA));

    /** Smjer pismena u urudžbenom zapisniku. Preslikava se na {@code EgopPismenoEntity.Smjer}. */
    public enum Smjer { ULAZNO, IZLAZNO }

    private final String slug;
    private final String vrstaPismenaNaziv;
    private final Smjer smjer;
    private final String naslov;
    private final boolean templateBacked;
    private final Set<ZupSection> requiredSections;

    StrDocumentType(String slug, String vrstaPismenaNaziv, Smjer smjer, String naslov,
                    boolean templateBacked, Set<ZupSection> requiredSections) {
        this.slug = slug;
        this.vrstaPismenaNaziv = vrstaPismenaNaziv;
        this.smjer = smjer;
        this.naslov = naslov;
        this.templateBacked = templateBacked;
        this.requiredSections = requiredSections;
    }

    /** Segment URL-a i naziv datoteke predloška. */
    public String slug() {
        return slug;
    }

    /** Naziv iz eGOP šifrarnika vrsta pismena — mora se poklapati znak za znak. */
    public String vrstaPismenaNaziv() {
        return vrstaPismenaNaziv;
    }

    public Smjer smjer() {
        return smjer;
    }

    /** Naslov koji se ispisuje na aktu; ne mora biti jednak nazivu vrste pismena. */
    public String naslov() {
        return naslov;
    }

    /** {@code false} samo za {@link #ZAHTJEV}, koji ima vlastiti tablični generator. */
    public boolean templateBacked() {
        return templateBacked;
    }

    public Set<ZupSection> requiredSections() {
        return requiredSections;
    }

    /**
     * Slugovi iz {@code RnDocumentType}, koji je nazive uzimao iz Knjige testiranja („Dopis o
     * namjeri", „Nalog za…"), a InfoDomov šifrarnik ih zove „Obavijest o…". Zadržani su da
     * postojeći linkovi i bookmarkovi ne puknu.
     */
    private static final java.util.Map<String, StrDocumentType> ALIASI = java.util.Map.of(
            "dopis-namjere", PRIJEDLOG_SUSPENZIJE,
            "nalog-suspenzija", SUSPENZIJA,
            "nalog-povlacenje", POVLACENJE);

    public static Optional<StrDocumentType> fromSlug(String slug) {
        for (StrDocumentType t : values()) {
            if (t.slug.equals(slug)) {
                return Optional.of(t);
            }
        }
        return Optional.ofNullable(ALIASI.get(slug));
    }

    /**
     * Reverse-lookup po nazivu vrste pismena — {@code egop_pismeno} redak čuva taj naziv, a
     * popis dokumenata iz njega izvodi slug (za ikone/rute na fronti). Prazno ako naziv ne
     * odgovara nijednoj vrsti (stariji zapis, ručna izmjena).
     */
    public static Optional<StrDocumentType> fromVrstaPismenaNaziv(String naziv) {
        if (naziv == null) {
            return Optional.empty();
        }
        for (StrDocumentType t : values()) {
            if (t.vrstaPismenaNaziv.equals(naziv)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * Koji akt nastaje iz prijelaza statusa RB-a. Jedno mjesto za oba potrošača — urudžbiranje
     * u eGOP i obavijest e-poštom — jer bi dvije kopije ovog mapiranja s vremenom razišle
     * dokument i poruku o istom događaju.
     *
     * <p>Izdavanje RB-a namjerno vraća prazno: ono se urudžbira u registracijskom toku, koji
     * PDF renderira <i>iz</i> urudžbenog broja i zato ne može ići ovim putem.
     *
     * @param byLessor je li promjenu pokrenuo iznajmljivač — isti okidač
     *                 {@code WITHDRAWAL} pokriva i opoziv i povlačenje po službenoj dužnosti
     */
    public static Optional<StrDocumentType> forTransition(RnStatus to, RnTrigger trigger,
                                                          boolean byLessor) {
        if (to == RnStatus.SUSPENDED) {
            return Optional.of(SUSPENZIJA);
        }
        if (to == RnStatus.ACTIVE && trigger == RnTrigger.REACTIVATE) {
            return Optional.of(REAKTIVACIJA);
        }
        if (to == RnStatus.WITHDRAWN) {
            return Optional.of(byLessor ? OPOZIV : POVLACENJE);
        }
        return Optional.empty();
    }

    /** Tipovi koji se renderiraju iz predloška — ono što {@link ZupTemplateLoader} učitava. */
    public static Set<StrDocumentType> templateBackedTypes() {
        EnumSet<StrDocumentType> result = EnumSet.noneOf(StrDocumentType.class);
        for (StrDocumentType t : values()) {
            if (t.templateBacked) {
                result.add(t);
            }
        }
        return result;
    }
}
