package com.str.backend.document;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Vrste pismena koje STR urudžbira u eGOP predmet, prema mailu InfoDoma od 22.07.2026.
 *
 * <p>{@link #vrstaPismenaNaziv()} je naš kanonski naziv: zapisuje se na {@code egop_pismeno}
 * (dio unique ključa, filter retry joba, popis dokumenata) i ide u {@code nazivPismena}.
 * Šifra se razrješava po {@link #egopVrstaNaziv()}, nazivu pod kojim je vrsta unesena u eGOP
 * šifrarnik; razlikuju se za zahtjev i reaktivaciju (MINT, 22. 10. 2026.). Ta su imena ranije
 * živjela na tri mjesta ({@code application.properties}, {@code EgopFilingService} i
 * {@code EgopClientMock}); ovaj enum je od sada jedini izvor.
 *
 * <p>{@link #smjer()} je smjer u urudžbenom zapisniku, a {@link #aktTijela()} tko je autor
 * dokumenta. Danas se poklapaju: podnesci stranke su ulazni, akti tijela izlazni. Do T15
 * (studeni 2026.) opoziv je bio ulazno pismeno s PDF-om akta tijela; sada su to dva pismena,
 * {@link #ZAHTJEV_OPOZIVA} i {@link #OPOZIV}.
 *
 * <p>{@link #requiredSections()} je <b>pravni minimum</b> koji {@link ZupTemplateLoader}
 * provjerava na startu. Sadržaj sekcije određuje predložak; ovaj popis samo brani da akt
 * ostane bez sekcije koju ZUP traži — npr. rješenje bez upute o pravnom lijeku, što po čl. 111.
 * ide na štetu tijela, ne stranke.
 *
 * <p>Sve obavijesti tijela imaju <b>istu strukturu</b> kao obavijest o dodjeli, po predlošku
 * MINT-a od 11.09.2026.: uvod, naslov akta i izreka bez naslova. Obrazloženje i uputu o pravnom
 * lijeku MINTS je u studenom 2026. izbacio i iz akata suspenzije i povlačenja (prigovor je točka
 * izreke). Adresat, prilozi i dostavna lista ne ispisuju se nigdje.
 * Podnesak stranke iz predloška ({@link #ZAHTJEV_OPOZIVA}) nije obavijest i zadržava vlastitu
 * strukturu po čl. 71.; očitovanja i prigovori ({@link #podnesakNaAkt}) predloška nemaju — njihov
 * je dokument ono što je stranka učitala.
 */
public enum StrDocumentType {

    /**
     * Podnesak stranke po čl. 71. ZUP-a, ne akt po čl. 98. Renderira ga
     * {@code SubmissionPdfGenerator} kao tablični obrazac, pa nema predloška.
     */
    ZAHTJEV("zahtjev", "Zahtjev za registracijski broj", Smjer.ULAZNO, false,
            "Zahtjev za dodjelu registracijskog broja",
            "ZAHTJEV ZA REGISTRACIJSKI BROJ", false, EnumSet.noneOf(ZupSection.class)),

    /**
     * Potvrda o činjenici iz službene evidencije (čl. 159.) — nema obrazloženja ni upute
     * o pravnom lijeku jer ništa ne dira u prava stranke.
     *
     * <p>Adresat i dostavna lista nisu obvezni: naručitelj ih je izbacio iz predloška
     * (predložak MINT-a od 11.09.2026.), jer se obavijest dostavlja u korisnički pretinac
     * stranke koja je u uvodu već imenovana. Isto vrijedi za sve ostale obavijesti — svaka
     * ima istu strukturu kao ova.
     */
    DODJELA("dodjela", "Obavijest o dodjeli registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O DODJELI REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Opoziv na zahtjev samog iznajmljivača ({@code REVOKED}): evidencijska obavijest, bez
     * pravnog lijeka.
     *
     * <p>Izlazni akt tijela (T15, studeni 2026.): pečati se kvalificiranim e-pečatom, otprema u
     * korisnički pretinac i dostavlja e-poštom kao i ostale obavijesti. Zahtjev stranke kojim je
     * opoziv pokrenut zasebno je, ulazno pismeno ({@link #ZAHTJEV_OPOZIVA}) i urudžbira se prije
     * ovog akta, u isti predmet. Od 22. 10. 2026. do T15 opoziv je bio jedno ulazno pismeno s PDF-om
     * akta tijela; takvi zapisi ostaju u {@code egop_pismeno} kao {@code ULAZNO} i retry ih šalje
     * po zapisanom smjeru.
     */
    OPOZIV("opoziv", "Obavijest o opozivu registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O OPOZIVU REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Podnesak stranke (čl. 71. ZUP-a): iznajmljivač je sam zatražio opoziv
     * svog registracijskog broja ({@code LessorRnActionService}). Ulazno pismeno, potpisuje ga
     * stranka; ne pečati se i ne otprema u korisnički pretinac. Nastaje iz istog prijelaza kao
     * {@link #OPOZIV} i urudžbira se prije njega ({@link #podnesak()}).
     */
    ZAHTJEV_OPOZIVA("zahtjev-opoziva", "Zahtjev za opoziv registracijskog broja", Smjer.ULAZNO, false,
            null, "ZAHTJEV ZA OPOZIV REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.NASLOV, ZupSection.UVOD, ZupSection.OBRAZLOZENJE)),

    /**
     * Poziv na očitovanje prije suspenzije. <b>Mora</b> nositi rok za očitovanje, koji po čl. 98.
     * st. 3 ide u izreku. Obrazloženje je MINTS izbacio (studeni 2026.): razlog je u točki 1., a
     * posljedice propuštanja roka u točkama 3. i 4.
     */
    PRIJEDLOG_SUSPENZIJE("prijedlog-suspenzije",
            "Obavijest o prijedlogu suspenzije registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O PRIJEDLOGU SUSPENZIJE REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Akt koji dira u prava stranke. Po predlošku MINTS-a (studeni 2026.) bez obrazloženja i bez
     * zasebne upute o pravnom lijeku: prigovor u roku od osam dana je točka izreke.
     */
    SUSPENZIJA("suspenzija", "Obavijest o suspenziji registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O SUSPENZIJI REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Povlačenje po službenoj dužnosti ({@code WITHDRAWN}). Kao suspenzija: prigovor u roku od osam
     * dana je točka izreke, obrazloženja nema (MINTS, studeni 2026.).
     */
    POVLACENJE("povlacenje", "Obavijest o povlačenju registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O POVLAČENJU REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Očitovanje iznajmljivača na prijedlog suspenzije (bug s testiranja MINTS-a, studeni 2026.): podnesak
     * stranke predan kroz {@code SuspensionObjectionService} dok je broj u {@code SUSPENSION_PROPOSED}.
     * Ulazno pismeno u predmetu postupka, iza akta na koji se odnosi ({@link #pobijaniAkt()}).
     *
     * <p>Kao i ostale vrste {@link #podnesakNaAkt}, <b>nema predloška</b>: glavni dokument pismena je
     * datoteka koju je iznajmljivač učitao (PDF, DOC ili DOCX), a njegove ostale datoteke idu kao
     * prilozi. Ne pečati se i ne otprema u korisnički pretinac.
     */
    OCITOVANJE_SUSPENZIJE("ocitovanje-suspenzije",
            "Očitovanje na prijedlog suspenzije registracijskog broja", Smjer.ULAZNO, false, null,
            "OČITOVANJE NA PRIJEDLOG SUSPENZIJE REGISTRACIJSKOG BROJA", false, EnumSet.noneOf(ZupSection.class)),

    /** Prigovor na suspenziju ({@code SUSPENDED}); kao {@link #OCITOVANJE_SUSPENZIJE}. */
    PRIGOVOR_SUSPENZIJE("prigovor-suspenzije",
            "Prigovor na suspenziju registracijskog broja", Smjer.ULAZNO, false, null,
            "PRIGOVOR NA SUSPENZIJU REGISTRACIJSKOG BROJA", false, EnumSet.noneOf(ZupSection.class)),

    /**
     * Očitovanje na prijedlog povlačenja ({@code WITHDRAWAL_PROPOSED}); kao {@link #OCITOVANJE_SUSPENZIJE}.
     * Naziv još potvrđuje MINTS. Naziv je ključ na {@code egop_pismeno}, pa ga promijeniti prije prvog
     * podneska na okolini s uključenim eGOP-om.
     */
    OCITOVANJE_POVLACENJA("ocitovanje-povlacenja",
            "Očitovanje na prijedlog povlačenja registracijskog broja", Smjer.ULAZNO, false, null,
            "OČITOVANJE NA PRIJEDLOG POVLAČENJA REGISTRACIJSKOG BROJA", false, EnumSet.noneOf(ZupSection.class)),

    /** Prigovor na povlačenje ({@code WITHDRAWN}); kao {@link #OCITOVANJE_SUSPENZIJE}. */
    PRIGOVOR_POVLACENJA("prigovor-povlacenja",
            "Prigovor na povlačenje registracijskog broja", Smjer.ULAZNO, false, null,
            "PRIGOVOR NA POVLAČENJE REGISTRACIJSKOG BROJA", false, EnumSet.noneOf(ZupSection.class)),

    /**
     * Ukidanje suspenzije — stranci u korist, pa nema obrazloženja ni upute.
     *
     * <p>Nije bila među 7 vrsta pismena iz InfoDomovog maila; MINT ju je unio na testu pod
     * nazivom „Obavijest o reaktiviranju…" (22. 10. 2026.), pa se urudžbira i otprema u KP kao
     * i ostale obavijesti.
     */
    REAKTIVACIJA("reaktivacija", "Obavijest o reaktivaciji registracijskog broja", Smjer.IZLAZNO, true,
            "Obavijest o reaktiviranju registracijskog broja",
            "OBAVIJEST O REAKTIVACIJI REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Postupak suspenzije obustavljen jer je stranka postupila po prijedlogu. Kao i
     * reaktivacija: stranci u korist, pa nema obrazloženja ni upute o pravnom lijeku.
     *
     * <p>Nije bila među 7 vrsta pismena iz InfoDomovog maila — nastala je uz dvofaznu
     * suspenziju, koja u tom trenutku nije postojala. MINT ju je unio na testu (22. 10. 2026.).
     */
    OBUSTAVA_SUSPENZIJE("obustava-suspenzije",
            "Obavijest o obustavi postupka suspenzije registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O OBUSTAVI POSTUPKA SUSPENZIJE REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Poziv na očitovanje prije povlačenja (T12), po uzoru na {@link #PRIJEDLOG_SUSPENZIJE}: rok za
     * očitovanje je u izreci (čl. 98. st. 3), posljedice propuštanja roka u točkama 3. i 4.
     *
     * <p>Nova vrsta pismena: dok je MINT ne unese u eGOP šifrarnik, urudžbira se pod zamjenskom šifrom
     * ({@code EGOP_SIFRA_PISMENA_FALLBACK}) ili se drži na popisu {@code EGOP_AKTI_BEZ_SIFRE}.
     */
    PRIJEDLOG_POVLACENJA("prijedlog-povlacenja",
            "Obavijest o prijedlogu povlačenja registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O PRIJEDLOGU POVLAČENJA REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Postupak povlačenja obustavljen (T12), po uzoru na {@link #OBUSTAVA_SUSPENZIJE}: stranci u korist,
     * pa nema obrazloženja ni upute o pravnom lijeku. Broj se uvijek vraća u ACTIVE. Nova vrsta
     * pismena u eGOP-u, kao {@link #PRIJEDLOG_POVLACENJA}.
     */
    OBUSTAVA_POVLACENJA("obustava-povlacenja",
            "Obavijest o obustavi postupka povlačenja registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O OBUSTAVI POSTUPKA POVLAČENJA REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA)),

    /**
     * Uvaženi prigovor na povlačenje (T12): povučeni broj ponovno je aktivan. Po uzoru na
     * {@link #REAKTIVACIJA} — stranci u korist, bez obrazloženja i upute, potpisuje tijelo. Nova vrsta
     * pismena u eGOP-u, kao {@link #PRIJEDLOG_POVLACENJA}.
     */
    REAKTIVACIJA_POVLACENJA("reaktivacija-povlacenja",
            "Obavijest o reaktivaciji povučenog registracijskog broja", Smjer.IZLAZNO, true, null,
            "OBAVIJEST O REAKTIVACIJI POVUČENOG REGISTRACIJSKOG BROJA", true,
            EnumSet.of(ZupSection.UVOD, ZupSection.IZREKA));

    /** Smjer pismena u urudžbenom zapisniku. Preslikava se na {@code EgopPismenoEntity.Smjer}. */
    public enum Smjer { ULAZNO, IZLAZNO }

    private final String slug;
    private final String vrstaPismenaNaziv;
    private final Smjer smjer;
    private final boolean aktTijela;
    private final String egopVrstaNaziv;
    private final String naslov;
    private final boolean templateBacked;
    private final Set<ZupSection> requiredSections;

    /**
     * @param egopVrstaNaziv naziv u eGOP šifrarniku; {@code null} = isti kao {@code vrstaPismenaNaziv}
     */
    StrDocumentType(String slug, String vrstaPismenaNaziv, Smjer smjer, boolean aktTijela,
                    String egopVrstaNaziv, String naslov, boolean templateBacked,
                    Set<ZupSection> requiredSections) {
        this.slug = slug;
        this.vrstaPismenaNaziv = vrstaPismenaNaziv;
        this.smjer = smjer;
        this.aktTijela = aktTijela;
        this.egopVrstaNaziv = egopVrstaNaziv != null ? egopVrstaNaziv : vrstaPismenaNaziv;
        this.naslov = naslov;
        this.templateBacked = templateBacked;
        this.requiredSections = requiredSections;
    }

    /** Segment URL-a i naziv datoteke predloška. */
    public String slug() {
        return slug;
    }

    /** Naš kanonski naziv vrste pismena — ključ na {@code egop_pismeno}; ne mijenjati. */
    public String vrstaPismenaNaziv() {
        return vrstaPismenaNaziv;
    }

    /** Naziv vrste u eGOP šifrarniku, po kojem se razrješava šifra. */
    public String egopVrstaNaziv() {
        return egopVrstaNaziv;
    }

    /** Smjer u urudžbenom zapisniku; samo izlazna pismena idu u korisnički pretinac. */
    public Smjer smjer() {
        return smjer;
    }

    /**
     * Dokument izdaje tijelo: zaglavlje s grbom, potpis tijela i ePečat. Podnesak stranke
     * ({@link #ZAHTJEV}, {@link #ZAHTJEV_OPOZIVA}, očitovanja i prigovori) to nema.
     */
    public boolean aktTijela() {
        return aktTijela;
    }

    /** Naslov koji se ispisuje na aktu; ne mora biti jednak nazivu vrste pismena. */
    public String naslov() {
        return naslov;
    }

    /**
     * {@code false} za {@link #ZAHTJEV}, koji ima vlastiti tablični generator, i za podneske na akt
     * ({@link #podnesakNaAkt}), čiji je dokument datoteka koju je stranka učitala.
     */
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
     * <p>Izdavanje RB-a ovdje ne dolazi: RB nastaje odmah kao {@code ACTIVE}, bez prijelaza, a
     * urudžbira se u registracijskom toku, koji PDF renderira <i>iz</i> urudžbenog broja.
     *
     * <p>Opoziv i povlačenje razlikuju se po ciljnom statusu, ne po akteru: opoziv iznajmljivača
     * vodi u {@code REVOKED}, povlačenje po službenoj dužnosti u {@code WITHDRAWN}.
     */
    public static Optional<StrDocumentType> forTransition(RnStatus to, RnTrigger trigger) {
        // U ACTIVE vodi više prijelaza, pa akte koji ondje završavaju bira okidač.
        if (to == RnStatus.ACTIVE && trigger == RnTrigger.REVOKE_WITHDRAWAL_PROPOSAL) {
            return Optional.of(OBUSTAVA_POVLACENJA);
        }
        if (to == RnStatus.WITHDRAWAL_PROPOSED) {
            return Optional.of(PRIJEDLOG_POVLACENJA);
        }
        if (to == RnStatus.ACTIVE && trigger == RnTrigger.REINSTATE) {
            return Optional.of(REAKTIVACIJA_POVLACENJA);
        }
        if (to == RnStatus.SUSPENSION_PROPOSED) {
            return Optional.of(PRIJEDLOG_SUSPENZIJE);
        }
        if (to == RnStatus.SUSPENDED) {
            return Optional.of(SUSPENZIJA);
        }
        if (to == RnStatus.ACTIVE && trigger == RnTrigger.REACTIVATE) {
            return Optional.of(REAKTIVACIJA);
        }
        if (to == RnStatus.ACTIVE && trigger == RnTrigger.REVOKE_PROPOSAL) {
            return Optional.of(OBUSTAVA_SUSPENZIJE);
        }
        if (to == RnStatus.REVOKED) {
            return Optional.of(OPOZIV);
        }
        if (to == RnStatus.WITHDRAWN) {
            return Optional.of(POVLACENJE);
        }
        return Optional.empty();
    }

    /**
     * Podnesak stranke kojim je postupak ovog akta pokrenut i koji se urudžbira <b>prije</b> njega,
     * u isti predmet. Zasad samo opoziv: zahtjev iznajmljivača pa obavijest tijela. Ostali akti
     * nastaju po službenoj dužnosti i podnesak nemaju.
     */
    public Optional<StrDocumentType> podnesak() {
        return this == OPOZIV ? Optional.of(ZAHTJEV_OPOZIVA) : Optional.empty();
    }

    /**
     * Sva pismena koja nastaju iz prijelaza, redom urudžbiranja: podnesak stranke (ako postoji) pa
     * akt iz {@link #forTransition}. Urudžba i popis dokumenata čitaju ovo; obavijest e-poštom ide
     * samo za akt.
     */
    public static List<StrDocumentType> pismenaForTransition(RnStatus to, RnTrigger trigger) {
        return forTransition(to, trigger)
                .map(akt -> akt.podnesak().map(podnesak -> List.of(podnesak, akt)).orElse(List.of(akt)))
                .orElse(List.of());
    }

    /**
     * Vrsta pismena za podnesak iznajmljivača (očitovanje ili prigovor, {@code SuspensionObjectionService})
     * predan dok je broj u {@code status}. Za ostale statuse prazno — ondje se podnesak ne može predati.
     *
     * <p>Za razliku od {@link #podnesak()}, ovaj podnesak ne pokreće postupak nego odgovara na akt koji je
     * broj doveo u {@code status} ({@link #pobijaniAkt()}), pa se urudžbira <b>iza</b> njega, u isti predmet.
     */
    public static Optional<StrDocumentType> podnesakNaAkt(RnStatus status) {
        if (status == null) {
            return Optional.empty();
        }
        return switch (status) {
            case SUSPENSION_PROPOSED -> Optional.of(OCITOVANJE_SUSPENZIJE);
            case SUSPENDED -> Optional.of(PRIGOVOR_SUSPENZIJE);
            case WITHDRAWAL_PROPOSED -> Optional.of(OCITOVANJE_POVLACENJA);
            case WITHDRAWN -> Optional.of(PRIGOVOR_POVLACENJA);
            default -> Optional.empty();
        };
    }

    /**
     * Akt na koji odgovara podnesak iz {@link #podnesakNaAkt}: onaj koji nastaje prijelazom u status uz
     * koji je podnesak predan. Za sve ostale vrste prazno.
     */
    public Optional<StrDocumentType> pobijaniAkt() {
        return switch (this) {
            case OCITOVANJE_SUSPENZIJE -> Optional.of(PRIJEDLOG_SUSPENZIJE);
            case PRIGOVOR_SUSPENZIJE -> Optional.of(SUSPENZIJA);
            case OCITOVANJE_POVLACENJA -> Optional.of(PRIJEDLOG_POVLACENJA);
            case PRIGOVOR_POVLACENJA -> Optional.of(POVLACENJE);
            default -> Optional.empty();
        };
    }

    /**
     * Akt izdaje službenik u postupku suspenzije odnosno povlačenja, pa ga potpisuje službena
     * osoba (naziv službene dužnosti i ime, MINTS, studeni 2026.), a ne samo tijelo. Dodjelu i
     * opoziv sustav izdaje automatski; reaktivacija (i reaktivacija povučenog broja) zasad zadržava
     * potpis tijela.
     */
    public boolean potpisujeSluzbenik() {
        return this == PRIJEDLOG_SUSPENZIJE || this == OBUSTAVA_SUSPENZIJE || this == SUSPENZIJA
                || this == PRIJEDLOG_POVLACENJA || this == OBUSTAVA_POVLACENJA || this == POVLACENJE;
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
