package com.str.backend.document;

import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.str.backend.auth.internal.InternalUserRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.dto.RnDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrDocumentServiceTest {

    private static final String RN = "HR180000123456789001";

    private RnRepository rnRepository;
    private LessorRepository lessorRepository;
    private SubmissionRepository submissionRepository;
    private RegistrationNumberLogRepository logRepository;
    private InternalUserRepository internalUsers;
    private StrDocumentService service;

    private static final String PROPIS_DODJELA =
            "članka 46. stavka 3. Zakona o ugostiteljskoj djelatnosti (Narodne novine, broj ___)";
    private static final String PROPIS_POSTUPCI =
            "članka 48. stavka 2. Zakona o ugostiteljskoj djelatnosti (Narodne novine, broj ___)";

    private static final DocumentProperties PROPERTIES = new DocumentProperties(
            new DocumentProperties.Tijelo("Ministarstvo turizma i sporta", "12345678901",
                    "Prisavlje 14", "Zagreb", "Uprava za turizam",
                    "članka 6. Uredbe (EU) 2024/1028"),
            new DocumentProperties.Potpisnik("Ivana Ivić", "Voditeljica postupka"),
            new DocumentProperties.Epecat(false, null, null, null, null),
            Map.of("suspenzija", "može se pokrenuti upravni spor u roku od 30 dana."),
            false, false, Map.of(
                    "dodjela", PROPIS_DODJELA,
                    "opoziv", PROPIS_POSTUPCI,
                    "prijedlog-suspenzije", PROPIS_POSTUPCI,
                    "obustava-suspenzije", PROPIS_POSTUPCI,
                    "suspenzija", PROPIS_POSTUPCI,
                    "povlacenje", PROPIS_POSTUPCI));

    private static final DocumentProperties PROPERTIES_S_PECATOM = new DocumentProperties(
            PROPERTIES.tijelo(), PROPERTIES.potpisnik(),
            new DocumentProperties.Epecat(true, "CN=Fina RDC 2020,O=Financijska agencija,C=HR",
                    "CN=KVALIFICIRANI ELEKTRONIČKI PEČAT MINISTARSTVA TURIZMA I SPORTA",
                    "SHA256withRSA", "https://provjera.example.hr/"),
            PROPERTIES.uputa(), false, false, PROPERTIES.propis());

    static Set<StrDocumentType> templateBackedTypes() {
        return StrDocumentType.templateBackedTypes();
    }

    /** Akti koje izdaje tijelo — podnesak stranke (prigovor) nema okvir tijela. */
    static Stream<StrDocumentType> aktiTijela() {
        return StrDocumentType.templateBackedTypes().stream()
                .filter(StrDocumentType::aktTijela);
    }

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        lessorRepository = mock(LessorRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        logRepository = mock(RegistrationNumberLogRepository.class);
        internalUsers = mock(InternalUserRepository.class);

        service = service(PROPERTIES);

        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(detail()));
        when(lessorRepository.findById(any())).thenReturn(Optional.of(lessor()));
        when(submissionRepository.findById(any())).thenReturn(Optional.empty());
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.empty());
    }

    private StrDocumentService service(DocumentProperties properties) {
        ZupTemplateLoader loader = new ZupTemplateLoader(properties);
        loader.loadAll();
        DocumentLabels labels = new DocumentLabels();
        return new StrDocumentService(rnRepository, lessorRepository, submissionRepository,
                logRepository, loader, new ZupContextFactory(properties, labels),
                new ZupDocumentRenderer(properties), labels, internalUsers);
    }

    @ParameterizedTest
    @MethodSource("templateBackedTypes")
    void render_producesReadablePdf_forEveryType(StrDocumentType type) throws IOException {
        String text = textOf(service.render(type, RN, "istek suglasnosti suvlasnika"));

        assertThat(text).contains(RN);
        assertThat(text).contains("Ministarstvo turizma i sporta");
        // Nijedan ${...} ne smije preživjeti do ispisa.
        assertThat(text).doesNotContain("${");
    }

    /**
     * Ovo je najtiši mogući kvar: PdfFonts pada na Helvetica/Cp1250 ako arial.ttf nije na
     * classpathu, PDF se i dalje generira, a č/ć/ž/š/đ nestanu iz akta.
     */
    @Test
    void render_preservesCroatianDiacritics() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "nepotpuna dokumentacija"));

        assertThat(text).contains("smještajnoj jedinici");
        assertThat(text).contains("Vijeća");
        assertThat(text).contains("službenoj dužnosti");
        assertThat(text).contains("Anić");
        assertThat(text).contains("iznajmljivač");
        assertThat(text).contains("kratkoročnog iznajmljivanja smještaja");
    }

    /**
     * Pravni lijek je po predlošku MINTS-a (studeni 2026.) točka izreke — prigovor u osam dana —
     * a ne zasebna sekcija s tekstom iz konfiguracije.
     */
    @Test
    void suspensionAct_carriesObjectionInOperativePart() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "razlog"));

        assertThat(text).contains("5. Protiv ove Obavijesti stranka može izjaviti prigovor nadležnom"
                + " upravnom tijelu u roku od osam dana od dana dostave ove Obavijesti.");
        assertThat(text).doesNotContain("Uputa o pravnom lijeku");
        assertThat(text).doesNotContain("upravni spor");
    }

    /**
     * Redoslijed sekcija na papiru. Renderer prolazi kroz {@code ZupSection.values()}, pa je ovo
     * regresijska brana za taj prolaz — izreka iza obrazloženja bila bi neispravan akt.
     */
    @Test
    void act_printsSectionsInZupOrder() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "razlog"));

        assertThat(text.indexOf("Ministarstvo turizma i sporta"))
                .isLessThan(text.indexOf("OBAVIJEST O SUSPENZIJI"));
        assertThat(text.indexOf("OBAVIJEST O SUSPENZIJI"))
                .isLessThan(text.indexOf("1. Suspendira se"));
        assertThat(text.indexOf("1. Suspendira se")).isLessThan(text.indexOf("5. Protiv ove Obavijesti"));
        // Naziv tijela velikim slovima je i u zaglavlju, pa potpis traži zadnje pojavljivanje.
        int potpis = text.lastIndexOf("MINISTARSTVO TURIZMA I SPORTA");
        assertThat(text.indexOf("5. Protiv ove Obavijesti")).isLessThan(potpis);
    }

    /** Čl. 98. st. 2 — uvod nosi OIB tijela i OIB stranke. */
    @Test
    void act_identifiesAuthorityAndPartyByOib() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "razlog"));

        assertThat(text).contains("OIB: 12345678901");
        assertThat(text).contains("OIB: 98765432109");
    }

    /** Čl. 98. st. 3 — rok za ispravak mora biti u izreci, s konkretnim datumom. */
    @Test
    void proposalAct_printsCorrectionDeadline() throws IOException {
        String text = textOf(service.render(StrDocumentType.PRIJEDLOG_SUSPENZIJE, RN, "razlog"));

        assertThat(text).contains("2. Poziva se stranka dostaviti očitovanje o razlozima suspenzije"
                + " registracijskog broja s ispravnim podacima i dokumentacijom najkasnije do 15. kolovoza 2026.");
        assertThat(text).doesNotContain("2026..");
    }

    /**
     * Prijedlog po predlošku MINTS-a: „Pokrenut je", stranka u točki 1. iza „dodijeljenog", automatska
     * suspenzija po isteku roka (t. 3.) i ishodi postupka po čl. 6. Uredbe (t. 4.).
     */
    @Test
    void proposalAct_followsMintsTemplate() throws IOException {
        String text = textOf(service.render(StrDocumentType.PRIJEDLOG_SUSPENZIJE, RN, "nepotpuni i/ili netočni podaci"));

        assertThat(text).contains("na temelju " + PROPIS_POSTUPCI + ", po službenoj dužnosti, izdaje");
        assertThat(text).doesNotContain("stranka: Ana Anić");
        assertThat(text).contains("1. Pokrenut je postupak suspenzije registracijskog broja " + RN
                + ", dodijeljenog na zahtjev stranke: Ana Anić, OIB: 98765432109, smještajnoj jedinici");
        assertThat(text).contains("zbog sljedećeg razloga: nepotpuni i/ili netočni podaci.");
        assertThat(text).contains("3. Registracijski broj automatski će se suspendirati ako očitovanje iz"
                + " točke 2. ove Obavijesti nije dostavljeno u ostavljenom roku.");
        assertThat(text).contains("4. Na temelju dostavljenog očitovanja postupak suspenzije registracijskog"
                + " broja bit će obustavljen ili će registracijski broj biti suspendiran odnosno povučen"
                + " sukladno članku 6. Uredbe (EU) 2024/1028");
        assertThat(text).contains("(SL L, 2024/1028, 29.4.2024.).");
    }

    /**
     * Regresija: {@code ZupContextFactory} traži natpis za status RB-a pri svakom renderu, a
     * {@code SUSPENSION_PROPOSED} ga nije imao — pa je upravo akt koji taj status objavljuje
     * pucao. Fixture inače vrti {@code SUSPENDED}, gdje se kvar nije vidio.
     */
    @ParameterizedTest
    @MethodSource("templateBackedTypes")
    void render_forProposedSuspension_doesNotFailOnMissingStatusLabel(StrDocumentType type)
            throws IOException {
        when(rnRepository.findDetail(RN))
                .thenReturn(Optional.of(detail(RnStatus.SUSPENSION_PROPOSED)));

        String text = textOf(service.render(type, RN, "nepotpuna dokumentacija"));

        assertThat(text).contains(RN);
        assertThat(text).doesNotContain("${");
    }

    /** Obustava zatvara postupak pokrenut prijedlogom — izreka to mora reći izrijekom. */
    @Test
    void revocationOfProposalAct_statesProceedingIsDiscontinued() throws IOException {
        String text = textOf(service.render(StrDocumentType.OBUSTAVA_SUSPENZIJE, RN,
                "dokumentacija je dopunjena"));

        assertThat(text).contains("1. Obustavlja se postupak suspenzije registracijskog broja " + RN
                + ", dodijeljenog na zahtjev stranke: Ana Anić, OIB: 98765432109, smještajnoj jedinici");
        assertThat(text).contains("ostaje važeći");
        // MINTS: bez razloga pokretanja postupka i bez točke 3. o oglašavanju.
        assertThat(text).doesNotContain("pokrenut zbog");
        assertThat(text).doesNotContain("dokumentacija je dopunjena");
        assertThat(text).doesNotContain("oglašavati");
    }

    /**
     * Zahtjev za opoziv je podnesak stranke: tijelo je adresat (naslov), stranka ga potpisuje, a
     * pečata tijela nema — ni kad je e-pečat uključen.
     */
    @Test
    void revocationRequest_isPartySubmissionWithReason() throws IOException {
        String text = textOf(service(PROPERTIES_S_PECATOM).render(StrDocumentType.ZAHTJEV_OPOZIVA, RN,
                "prodajem stan"));

        assertThat(text).contains("ZAHTJEV ZA OPOZIV REGISTRACIJSKOG BROJA");
        assertThat(text).contains("Podnositelj: Ana Anić, OIB: 98765432109");
        assertThat(text).contains("Predmet: zahtjev za opoziv registracijskog broja " + RN);
        assertThat(text).contains("Podnositelj traži opoziv registracijskog broja " + RN);
        assertThat(text).contains("„Apartman Sunce\"");
        assertThat(text).contains("Kao razlog opoziva podnositelj navodi: prodajem stan");
        assertThat(text).doesNotContain("REPUBLIKA HRVATSKA");
        assertThat(text).doesNotContain("KVALIFICIRANI ELEKTRONIČKI PEČAT");
        assertThat(text).doesNotContain("${");
    }

    /** Bez razloga stranke redak otpada — natpis okidača ne smije joj se podmetnuti kao razlog. */
    @Test
    void revocationRequest_withoutReason_omitsReasonLine() throws IOException {
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "ACTIVE", "REVOKED", "REVOCATION",
                        "NIAS:98765432109", null)));

        String text = textOf(service.render(StrDocumentType.ZAHTJEV_OPOZIVA, RN, null));

        assertThat(text).contains("Podnositelj traži opoziv");
        assertThat(text).doesNotContain("Kao razlog opoziva");
        assertThat(text).doesNotContain("na zahtjev iznajmljivača");
        assertThat(text).doesNotContain("razlog nije naveden");
    }

    /** Obavijest o opozivu ostaje akt tijela s grbom i pečatom (izlazno pismeno od T15). */
    @Test
    void revocationNotice_isActOfTheAuthority() throws IOException {
        String text = textOf(service(PROPERTIES_S_PECATOM).render(StrDocumentType.OPOZIV, RN, null));

        assertThat(text).contains("REPUBLIKA HRVATSKA");
        assertThat(text).contains("OBAVIJEST O OPOZIVU REGISTRACIJSKOG BROJA");
        assertThat(text).contains("opozvan je registracijski broj " + RN);
    }

    /** Razlog iz revizijskog traga za okidače uvedene uz dvofaznu suspenziju. */
    @Test
    void render_fallsBackToLabelsForTwoPhaseTriggers() throws IOException {
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "SUSPENSION_PROPOSED", "SUSPENDED",
                        "DEADLINE_EXCEEDED", null, null)));

        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, null));

        assertThat(text).contains("istek roka za očitovanje");
    }

    /**
     * Čl. 98. st. 8: ovjera je moguća samo kvalificiranim elektroničkim pečatom. Dok pečata
     * nema, akt ga ne smije tvrditi.
     */
    @Test
    void act_omitsSealBlock_whileSealIsDisabled() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "razlog"));

        assertThat(text).doesNotContain("Vrijeme izdavanja");
        assertThat(text).doesNotContain("Kontrolni broj");
        // Potpis je naziv tijela; konfigurirano ime ide ispod, funkcija se više ne ispisuje.
        assertThat(text).contains("Ivana Ivić");
        assertThat(text).doesNotContain("Voditeljica postupka");
    }

    /**
     * PDF/A-1b: PDF 1.4, sRGB output intent i XMP s oznakom razine. Bez toga eSeal može odbiti akt
     * s PDF_NOT_PDFA. Ovo provjerava oznake, ne punu sukladnost — nju potvrđuje validator ili servis.
     */
    @ParameterizedTest
    @MethodSource("aktiTijela")
    void pdfa_marksDocumentAsPdfA1b(StrDocumentType type) throws IOException {
        DocumentProperties pdfa = new DocumentProperties(PROPERTIES.tijelo(), PROPERTIES.potpisnik(),
                PROPERTIES.epecat(), PROPERTIES.uputa(), false, true, PROPERTIES.propis());

        byte[] pdf = service(pdfa).render(type, RN, "razlog");

        assertThat(new String(pdf, 0, 8, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.4");
        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getCatalog().getAsArray(PdfName.OUTPUTINTENTS)).isNotNull();
            String xmp = new String(reader.getMetadata(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(xmp).contains("pdfaid:part").contains("1");
            assertThat(xmp).containsPattern("pdfaid:conformance[^A-Za-z]*B");
        } finally {
            reader.close();
        }
    }

    @Test
    void withoutPdfa_documentIsUnchanged() throws IOException {
        byte[] pdf = service.render(StrDocumentType.SUSPENZIJA, RN, "razlog");

        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getCatalog().getAsArray(PdfName.OUTPUTINTENTS)).isNull();
        } finally {
            reader.close();
        }
    }

    /** Blok po uzoru na Poreznu upravu: podaci o pečatu, broj zapisa, QR i tekst provjere. */
    @Test
    void act_printsSealBlock_whenSealIsEnabled() throws IOException {
        byte[] pdf = service(PROPERTIES_S_PECATOM).render(StrDocumentType.DODJELA, RN, null);
        String text = textOf(pdf);

        // Ekstraktor susjedne ćelije tablice spaja bez razmaka („natpis:vrijednost").
        assertThat(text).contains("Vrijeme izdavanja:");
        assertThat(text).contains("Izdavatelj certifikata:CN=Fina RDC 2020");
        assertThat(text).contains("Algoritam potpisa:SHA256withRSA");
        assertThat(text).contains("Broj zapisa:");
        assertThat(text).containsPattern("Kontrolni broj:\\d{8}");
        assertThat(text).contains("Na internet adresi https://provjera.example.hr/ možete provjeriti");
        assertThat(text).contains("Ministarstvo turizma i sporta potvrđuje točnost isprave");
        // Grb u zaglavlju, grb u bloku i QR kod.
        assertThat(imageCount(pdf)).isEqualTo(3);
    }

    /** Uključen pečat bez podataka o certifikatu ne smije proći nezapaženo. */
    @Test
    void sealBlock_withoutCertificateConfig_showsVisibleMarker() throws IOException {
        DocumentProperties bezCertifikata = new DocumentProperties(PROPERTIES.tijelo(),
                PROPERTIES.potpisnik(), new DocumentProperties.Epecat(true, null, null, null, null),
                PROPERTIES.uputa(), false, false, PROPERTIES.propis());

        String text = textOf(service(bezCertifikata).render(StrDocumentType.DODJELA, RN, null));

        assertThat(text).contains("[nije konfigurirano: str.documents.epecat.izdavatelj-certifikata]");
        assertThat(text).contains("[nije konfigurirano: str.documents.epecat.url-provjere]");
    }

    /**
     * Obavijest o dodjeli po predlošku MINT-a od 11.09.2026.: bez adresata, bez naslova izreke,
     * priloga i dostavne liste, s novim tekstom uvoda i točaka 1 i 2.
     */
    @Test
    void dodjela_followsMinistryTemplate() throws IOException {
        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(detail(RnStatus.ACTIVE)));

        String text = textOf(service.render(StrDocumentType.DODJELA, RN, null));

        assertThat(text).contains("Ministarstvo turizma i sporta, OIB: 12345678901, na temelju "
                + PROPIS_DODJELA + ", na zahtjev Ana Anić, OIB: 98765432109, izdaje");
        assertThat(text).contains("OBAVIJEST O DODJELI REGISTRACIJSKOG BROJA");
        assertThat(text).contains("1. Smještajnoj jedinici „Apartman Sunce\", na adresi Ilica 1,"
                + " Zagreb, skupine: Objekti u kojima se pružaju ugostiteljske usluge u domaćinstvu,"
                + " vrste: Apartman, dodijeljen je registracijski broj " + RN + ".");
        assertThat(text).contains("2. Registracijski broj upisan je u integrirani informacijski"
                + " sustav turizma dana 01. ožujka 2026. godine.");
        assertThat(text).contains("3. Sukladno članku 46. stavku 5. Zakona o ugostiteljskoj djelatnosti"
                + " registracijski broj se navodi u svakom oglasu smještajne jedinice na internetskim"
                + " platformama za kratkoročno iznajmljivanje smještaja.");
        assertThat(text).contains("Ova obavijest izdaje se automatski iz integriranog informacijskog"
                + " sustava turizma.");
        assertThat(text).doesNotContain("I Z R E K A");
        assertThat(text).doesNotContain("Prilozi");
        assertThat(text).doesNotContain("Dostaviti");
        assertThat(text).doesNotContain("u postupku pokrenutom");
        assertThat(text).doesNotContain("Utvrđuje se");
    }

    /**
     * Naziv tijela iz env-a upisan malim slovom (CDU, 18.09.2026.) ne smije otvoriti uvod akta
     * malim slovom; zaglavlje ga i dalje piše velikim slovima.
     */
    @Test
    void uvod_pocinjeVelikimSlovom_iKadJeNazivUKonfiguracijiMalim() throws IOException {
        DocumentProperties malimSlovom = new DocumentProperties(
                new DocumentProperties.Tijelo("ministarstvo turizma i sporta", "12345678901",
                        "Prisavlje 14", "Zagreb", "Uprava za turizam",
                        "članka 6. Uredbe (EU) 2024/1028"),
                PROPERTIES.potpisnik(), PROPERTIES.epecat(), PROPERTIES.uputa(), false, false, PROPERTIES.propis());
        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(detail(RnStatus.SUSPENSION_PROPOSED)));

        String text = textOf(service(malimSlovom).render(StrDocumentType.PRIJEDLOG_SUSPENZIJE, RN, "razlog"));

        assertThat(text).contains("Ministarstvo turizma i sporta, OIB: 12345678901");
        assertThat(text).doesNotContain("ministarstvo turizma i sporta");
        assertThat(text).contains("REPUBLIKA HRVATSKA MINISTARSTVO TURIZMA I SPORTA");
    }

    /**
     * Svaka obavijest tijela ima istu strukturu kao dodjela: bez adresata, bez naslova izreke,
     * priloga i dostavne liste, i bez uredskog uvodnog nabrajanja predmeta.
     */
    @ParameterizedTest
    @MethodSource("aktiTijela")
    void everyNotice_hasSameStructureAsDodjela(StrDocumentType type) throws IOException {
        String text = textOf(service.render(type, RN, "razlog"));

        assertThat(text).doesNotContain("I Z R E K A");
        assertThat(text).doesNotContain("Prilozi");
        assertThat(text).doesNotContain("Dostaviti");
        assertThat(text).doesNotContain("u postupku pokrenutom");
        assertThat(text).doesNotContain("u predmetu");
        // Adresat je bio jedini blok s imenom stranke prije uvoda; ostaje samo imenovanje u uvodu.
        assertThat(text.split("Ana Anić", -1).length - 1).isLessThanOrEqualTo(2);
        // Objekt se identificira kao u dodjeli — skupinom i vrstom.
        assertThat(text).contains("skupine: Objekti u kojima se pružaju ugostiteljske usluge"
                + " u domaćinstvu, vrste: Apartman");
        // Naš registar se posvuda zove istim imenom — „registar" kao samostalna riječ ne ostaje
        // (granica riječi je bitna: „registracijski" počinje istim slovima).
        assertThat(text).doesNotContainPattern("\\bregistar\\b|\\bregistra\\b|\\bregistru\\b");
    }

    /**
     * MINTS (studeni 2026.): suspenzija i povlačenje bez obrazloženja i bez zasebne upute — prigovor
     * u roku od osam dana je točka izreke, a troškova postupka nema.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SUSPENZIJA", "POVLACENJE"})
    void adverseActs_carryObjectionWithoutReasoning(String slug) throws IOException {
        String text = textOf(service.render(StrDocumentType.valueOf(slug), RN, "razlog"));

        assertThat(text).doesNotContain("Obrazloženje");
        assertThat(text).doesNotContain("Uputa o pravnom lijeku");
        assertThat(text).doesNotContain("Troškovi");
        assertThat(text).contains("može izjaviti prigovor nadležnom upravnom tijelu u roku od osam dana"
                + " od dana dostave ove Obavijesti.");
        String dan = slug.equals("SUSPENZIJA") ? "suspenzije" : "povlačenja";
        assertThat(text).contains("prestaje važiti danom " + dan
                + " i briše se iz integriranog informacijskog sustava turizma s danom " + danas());
    }

    @Test
    void withdrawalAct_followsMintsTemplate() throws IOException {
        String text = textOf(service.render(StrDocumentType.POVLACENJE, RN,
                "dostavljene su nevjerodostojne ili nevaljane informacije"));

        assertThat(text).contains("1. Povlači se registracijski broj " + RN + ", dodijeljen na zahtjev"
                + " stranke: Ana Anić, OIB: 98765432109, smještajnoj jedinici");
        assertThat(text).contains("zbog sljedećeg razloga: dostavljene su nevjerodostojne ili nevaljane informacije.");
        assertThat(text).contains("3. Sukladno članku 6. stavku 6. Uredbe (EU) 2024/1028");
        assertThat(text).contains("krajnjom nepažnjom nije ispravio tražene informacije");
        assertThat(text).contains("zahtijeva da bez nepotrebne odgode uklone sve ponude");
        assertThat(text).doesNotContain("javnog dijela");
        assertThat(text).doesNotContain("trajno");
    }

    @Test
    void suspensionAct_followsMintsTemplate() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "razlog"));

        assertThat(text).contains(", dodijeljen na zahtjev stranke: Ana Anić, OIB: 98765432109, smještajnoj jedinici");
        assertThat(text).contains("3. Sukladno članku 6. stavku 8. Uredbe (EU) 2024/1028 Europskog parlamenta"
                + " i Vijeća od 11. travnja 2024.");
        assertThat(text).contains("4. Nakon primitka te provjere točnosti, potpunosti i ispravnosti"
                + " dostavljenih informacija i dokumentacije nadležna tijela ponovno će aktivirati"
                + " registracijski broj.");
        assertThat(text).doesNotContain("Suspenzija ne briše");
    }

    /** Prijedlog suspenzije je poziv na očitovanje: MINTS je izbacio obrazloženje, uputu nikad nije imao. */
    @Test
    void proposalAct_hasNoReasoningOrLegalRemedy() throws IOException {
        String text = textOf(service.render(StrDocumentType.PRIJEDLOG_SUSPENZIJE, RN, "razlog"));

        assertThat(text).doesNotContain("Obrazloženje");
        assertThat(text).doesNotContain("Uputa o pravnom lijeku");
    }

    /** Opoziv: bez „javnog dijela", s danom opoziva i s platformama za kratkoročno iznajmljivanje. */
    @Test
    void revocationNotice_followsMintsTemplate() throws IOException {
        String text = textOf(service.render(StrDocumentType.OPOZIV, RN, null));

        assertThat(text).contains("na temelju " + PROPIS_POSTUPCI + ", na zahtjev Ana Anić");
        assertThat(text).contains("2. Registracijski broj prestaje važiti danom opoziva i briše se iz"
                + " integriranog informacijskog sustava turizma s danom " + danas());
        assertThat(text).contains("Za ponovno oglašavanje smještajne jedinice na internetskim platformama"
                + " za kratkoročno iznajmljivanje smještaja potrebno je");
        assertThat(text).contains("Ova obavijest izdaje se automatski iz integriranog informacijskog"
                + " sustava turizma.");
        assertThat(text).doesNotContain("javnog dijela");
    }

    /** Propis o nadležnosti po vrsti akta; reaktivacija (bez unosa) uzima zajednički propis tijela. */
    @ParameterizedTest
    @MethodSource("aktiTijela")
    void everyAct_citesLegalBasisForItsType(StrDocumentType type) throws IOException {
        String text = textOf(service.render(type, RN, "razlog"));

        String ocekivano = switch (type) {
            case DODJELA -> PROPIS_DODJELA;
            case REAKTIVACIJA -> "članka 6. Uredbe (EU) 2024/1028";
            default -> PROPIS_POSTUPCI;
        };
        assertThat(text).contains("na temelju " + ocekivano + ",");
    }

    /** MINTS-ove tipfelere i stari naziv djelatnosti nijedan od šest akata ne smije ponoviti. */
    @ParameterizedTest
    @ValueSource(strings = {"DODJELA", "OPOZIV", "PRIJEDLOG_SUSPENZIJE", "OBUSTAVA_SUSPENZIJE",
            "SUSPENZIJA", "POVLACENJE"})
    void mintsActs_haveNoTyposOrOldTerm(String slug) throws IOException {
        String text = textOf(service.render(StrDocumentType.valueOf(slug), RN, "razlog"));

        assertThat(text).doesNotContain("nepaožnjom", "roko ", "zahtjeva da", "listopda", "kratkoročni najam",
                "kratkoročnog najma", "Temeljem", "stranka: Ana Anić", "..");
    }

    /**
     * Akt službenika potpisuje službenik koji je napravio prijelaz (MINTS: osobno ime službene
     * osobe), a ne tijelo. Naziv službene dužnosti nema izvora, pa se ne ispisuje.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PRIJEDLOG_SUSPENZIJE", "OBUSTAVA_SUSPENZIJE", "SUSPENZIJA", "POVLACENJE"})
    void officialAct_isSignedByTheOfficial(String slug) throws IOException {
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "ACTIVE", "SUSPENSION_PROPOSED", "INSPECTION",
                        "INTERNAL:12345678903", null)));
        when(internalUsers.findNames(Set.of("12345678903"))).thenReturn(Map.of("12345678903", "Ana Službenica"));

        String text = textOf(service.render(StrDocumentType.valueOf(slug), RN, "razlog"));

        assertThat(text).endsWith("Ana Službenica ");
        // Naziv tijela velikim slovima ostaje samo u zaglavlju.
        assertThat(text.split("MINISTARSTVO TURIZMA I SPORTA", -1).length - 1).isEqualTo(1);
        assertThat(text).doesNotContain("Ivana Ivić");
    }

    /** Suspenziju istekom roka radi sustav — potpisuje je službenik koji je postupak pokrenuo. */
    @Test
    void systemSuspension_isSignedByTheOfficialWhoProposedIt() throws IOException {
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "SUSPENSION_PROPOSED", "SUSPENDED",
                        "DEADLINE_EXCEEDED", "SYSTEM", "Rok za rješavanje nepravilnosti istekao.")));
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, "SUSPENSION_PROPOSED"))
                .thenReturn(Optional.of(RegistrationNumberLogEntity.transition(RN, "ACTIVE",
                        "SUSPENSION_PROPOSED", "INSPECTION", "INTERNAL:12345678903", null)));
        when(internalUsers.findNames(Set.of("12345678903"))).thenReturn(Map.of("12345678903", "Ana Službenica"));

        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN,
                "Rok za rješavanje nepravilnosti istekao."));

        assertThat(text).endsWith("Ana Službenica ");
        // Razlog je materijalni razlog s prijedloga, ne bilješka joba koju pozivatelj proslijedi.
        assertThat(text).contains("zbog sljedećeg razloga: nalaz inspekcijskog nadzora.");
        assertThat(text).doesNotContain("Rok za rješavanje");
    }

    /** Nedostupna shema str ne smije srušiti akt — potpis tada ostaje naziv tijela. */
    @Test
    void officialAct_withoutReadableOfficial_isSignedByTheAuthority() throws IOException {
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "ACTIVE", "WITHDRAWN", "WITHDRAWAL",
                        "INTERNAL:12345678903", "drugi razlozi")));
        when(internalUsers.findNames(any())).thenThrow(new DataAccessResourceFailureException("permission denied"));

        String text = textOf(service.render(StrDocumentType.POVLACENJE, RN, null));

        assertThat(text).endsWith("MINISTARSTVO TURIZMA I SPORTA Ivana Ivić ");
    }

    /** Okvir svih akata tijela: grb, tijelo velikim slovima, dugi datum, bez podnožja. */
    @ParameterizedTest
    @MethodSource("aktiTijela")
    void everyAct_hasMinistryFrame(StrDocumentType type) throws IOException {
        byte[] pdf = service.render(type, RN, "razlog");
        String text = textOf(pdf);

        // Zaglavlje je tablica, a OpenPDF tekst tablica zapisuje iza ostatka stranice — pa se
        // položaj ne može provjeriti redoslijedom teksta, samo sadržajem.
        assertThat(text).contains("REPUBLIKA HRVATSKA MINISTARSTVO TURIZMA I SPORTA");
        assertThat(text).doesNotContain("Uprava za turizam");
        assertThat(text).doesNotContain("stranica 1");
        assertThat(text).containsPattern("Zagreb, \\d{2}\\. [a-zčćđšž]+ \\d{4}\\.");
        assertThat(imageCount(pdf)).isEqualTo(1);
    }

    /**
     * Prigovor je podnesak stranke (čl. 71.): tijelo mu je adresat, ne izdavatelj. Ne smije
     * nositi grb ni potpis tijela, a pečat tijela ni kad je uključen.
     */
    @Test
    void partySubmission_hasNoMinistryFrameOrSeal() throws IOException {
        byte[] pdf = service(PROPERTIES_S_PECATOM).render(StrDocumentType.PRIGOVOR, RN, "razlog");
        String text = textOf(pdf);

        assertThat(text).doesNotContain("REPUBLIKA HRVATSKA");
        assertThat(text).doesNotContain("MINISTARSTVO TURIZMA I SPORTA");
        assertThat(text).doesNotContain("Vrijeme izdavanja");
        assertThat(imageCount(pdf)).isZero();
        // Potpisuje podnositelj.
        assertThat(text).endsWith("Ana Anić ");
    }

    /** eGOP-ov JOP u desnom kutu zaglavlja, samo kad je pismeno urudžbirano. */
    @Test
    void header_printsKlasaUrbrojAndJop_whenFiled() throws IOException {
        byte[] pdf = service.render(StrDocumentType.DODJELA, RN, null,
                new FilingReference("334-06/26-10/1", "529-06-03/01-26-2", 21748084));
        String text = textOf(pdf);

        assertThat(text).contains("P/21748084");
        // „Generirati kod kao u eGOP-u" (MINTS): grb i barkod oznake pismena.
        assertThat(imageCount(pdf)).isEqualTo(2);
        assertThat(text).contains("KLASA: 334-06/26-10/1 URBROJ: 529-06-03/01-26-2 Zagreb, ");
    }

    @Test
    void header_omitsJop_beforeFiling() throws IOException {
        byte[] pdf = service.render(StrDocumentType.DODJELA, RN, null,
                new FilingReference("334-06/26-10/1", null));
        String text = textOf(pdf);

        assertThat(text).doesNotContain("P/");
        assertThat(imageCount(pdf)).isEqualTo(1);
        assertThat(text).doesNotContain("URBROJ");
    }

    /**
     * Suspenzija je dvofazna: materijalni razlog stoji na prijelazu u {@code SUSPENSION_PROPOSED},
     * a zadnji prijelaz nosi samo procesni okidač. Iz zadnjeg zapisa je akt ispisivao „zbog
     * sljedećeg razloga: istek roka za očitovanje", dakle rok umjesto razloga.
     */
    @Test
    void suspensionAct_citesGroundFromProposal_notTheDeadlineTrigger() throws IOException {
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, "SUSPENSION_PROPOSED"))
                .thenReturn(Optional.of(RegistrationNumberLogEntity.transition(RN, "ACTIVE",
                        "SUSPENSION_PROPOSED", "CONSENT_EXPIRY", null, null)));
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "SUSPENSION_PROPOSED", "SUSPENDED",
                        "DEADLINE_EXCEEDED", null, null)));

        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, null));

        assertThat(text).contains("zbog sljedećeg razloga: istek suglasnosti suvlasnika");
        assertThat(text).doesNotContain("istek roka za očitovanje");
    }

    /** Obustava razlog ne ispisuje (MINTS) — ni razlog prijedloga ni vlastiti procesni okidač. */
    @Test
    void revocationAct_citesNoGround() throws IOException {
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, "SUSPENSION_PROPOSED"))
                .thenReturn(Optional.of(RegistrationNumberLogEntity.transition(RN, "ACTIVE",
                        "SUSPENSION_PROPOSED", "INCOMPLETE_DOCUMENTATION", null, null)));
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "SUSPENSION_PROPOSED", "ACTIVE",
                        "REVOKE_PROPOSAL", null, null)));

        String text = textOf(service.render(StrDocumentType.OBUSTAVA_SUSPENZIJE, RN, null));

        assertThat(text).doesNotContain("nepotpuni i/ili netočni podaci");
        assertThat(text).doesNotContain("obustava postupka suspenzije");
    }

    /** Jednofazna suspenzija iz starijih podataka nema prijedlog u tragu — vrijedi zadnji zapis. */
    @Test
    void suspensionAct_withoutProposalInTrail_fallsBackToLastEntry() throws IOException {
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "ACTIVE", "SUSPENDED",
                        "INSPECTION", null, null)));

        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, null));

        assertThat(text).contains("nalaz inspekcijskog nadzora");
    }

    /**
     * Obavijest o dodjeli renderira se i na zahtjev, dugo nakon izdavanja — datum u zaglavlju
     * mora ostati datum izdavanja RB-a, inače ista obavijest svaki dan izlazi s novim datumom.
     */
    @Test
    void dodjela_headerCarriesIssueDate_notToday() throws IOException {
        String text = textOf(service.render(StrDocumentType.DODJELA, RN, null));

        assertThat(text).contains("Zagreb, 01. ožujka 2026.");
    }

    /** Akt životnog ciklusa nastaje u trenutku prijelaza, pa nosi datum rendera. */
    @Test
    void lifecycleAct_headerCarriesRenderDate() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "razlog"));

        assertThat(text).contains("Zagreb, " + danas());
    }

    private static String danas() {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern(
                "dd. MMMM yyyy.", java.util.Locale.forLanguageTag("hr")));
    }

    /**
     * Jednostranični akt po predlošku naručitelja nema podnožje, ali višestranični mora nositi
     * KLASU i broj stranice — inače se odvojen list ne može povezati s predmetom.
     */
    @Test
    void multiPageAct_carriesKlasaAndPageNumberInFooter() throws IOException {
        byte[] pdf = service(PROPERTIES_S_PECATOM).render(StrDocumentType.SUSPENZIJA, RN, "razlog",
                new FilingReference("334-06/26-10/1", null));
        String text = textOf(pdf);

        assertThat(pageCount(pdf)).isEqualTo(2);
        assertThat(text).contains("KLASA: 334-06/26-10/1 · stranica 1 od 2");
        assertThat(text).contains("KLASA: 334-06/26-10/1 · stranica 2 od 2");
    }

    @Test
    void singlePageAct_hasNoFooter() throws IOException {
        byte[] pdf = service.render(StrDocumentType.DODJELA, RN, null);

        assertThat(pageCount(pdf)).isEqualTo(1);
        assertThat(textOf(pdf)).doesNotContain("stranica");
    }

    /**
     * QR koji vodi na oznaku „nije konfigurirano" gori je od nikakvog: kod se ne crta dok URL
     * portala za provjeru nije postavljen. U bloku tada ostaju grb zaglavlja i grb pečata.
     */
    @Test
    void sealBlock_withoutVerificationUrl_printsNoScannableCode() throws IOException {
        DocumentProperties bezUrla = new DocumentProperties(PROPERTIES.tijelo(),
                PROPERTIES.potpisnik(),
                new DocumentProperties.Epecat(true, "CN=Fina RDC 2020", "CN=PEČAT", null, null),
                PROPERTIES.uputa(), false, false, PROPERTIES.propis());

        byte[] pdf = service(bezUrla).render(StrDocumentType.DODJELA, RN, null);

        assertThat(imageCount(pdf)).isEqualTo(2);
        assertThat(textOf(pdf)).contains("[nije konfigurirano: str.documents.epecat.url-provjere]");
    }

    /** Razlog se uzima iz revizijskog traga kad pozivatelj ne proslijedi svoj. */
    @Test
    void render_withoutReason_fallsBackToAuditTrail() throws IOException {
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "ACTIVE", "SUSPENDED",
                        "INCOMPLETE_DOCUMENTATION", null, null)));

        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, null));

        assertThat(text).contains("nepotpuni i/ili netočni podaci");
    }

    /**
     * Razlog povlačenja (MINTS) nije okidač — prijelaz nosi WITHDRAWAL, a natpis razloga stoji u
     * {@code reason} zapisa ({@code RnService.withdraw}). Akt ga ispisuje umjesto natpisa okidača.
     */
    @Test
    void withdrawalAct_citesReasonFromLog_notTheTrigger() throws IOException {
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "ACTIVE", "WITHDRAWN", "WITHDRAWAL", null,
                        "namjerno ili krajnjom nepažnjom nisu ispravljeni podaci")));

        String text = textOf(service.render(StrDocumentType.POVLACENJE, RN, null));

        assertThat(text).contains("zbog sljedećeg razloga: namjerno ili krajnjom nepažnjom nisu ispravljeni podaci");
    }

    /**
     * Non-EU iznajmljivač bez OIB-a, bez naziva objekta i bez roka — sve legitimna stanja.
     * Renderira se svaki tip, jer bi neriješen ${...} inače pukao tek u produkciji nad
     * podacima koje testni fixture ne pokriva.
     */
    @ParameterizedTest
    @MethodSource("templateBackedTypes")
    void render_sparseData_stillProducesCompletePdf(StrDocumentType type) throws IOException {
        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(sparseDetail()));
        when(lessorRepository.findById(any())).thenReturn(Optional.empty());

        String text = textOf(service.render(type, RN, null));

        assertThat(text).doesNotContain("${");
        assertThat(text).contains(RN);
        // Čl. 98. st. 2 traži OIB „ako joj je dodijeljen" — bez njega akt to mora reći, ne šutjeti.
        assertThat(text).contains("bez dodijeljenog OIB-a");
    }

    /** Bez roka u bazi izreka mora nositi zakonski default, ne prazninu. */
    @Test
    void proposalAct_withoutDeadline_printsDefaultPeriod() throws IOException {
        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(sparseDetail()));

        String text = textOf(service.render(StrDocumentType.PRIJEDLOG_SUSPENZIJE, RN, null));

        assertThat(text).contains("u roku od 15 dana");
    }

    @Test
    void render_unknownRn_throws() {
        when(rnRepository.findDetail("HR180000000000000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.render(StrDocumentType.SUSPENZIJA, "HR180000000000000000", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void render_zahtjev_throws_becauseItHasItsOwnGenerator() {
        assertThatThrownBy(() -> service.render(StrDocumentType.ZAHTJEV, RN, null))
                .isInstanceOf(DocumentTemplateException.class);
    }

    /** Slike (XObject /Image) na prvoj stranici — grb i, uz uključen pečat, grb i QR u bloku. */
    private static int imageCount(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfDictionary resources = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
            PdfDictionary xobjects = resources.getAsDict(PdfName.XOBJECT);
            if (xobjects == null) {
                return 0;
            }
            int count = 0;
            for (PdfName name : xobjects.getKeys()) {
                PdfObject obj = PdfReader.getPdfObject(xobjects.get(name));
                if (obj instanceof PdfDictionary dict
                        && PdfName.IMAGE.equals(dict.get(PdfName.SUBTYPE))) {
                    count++;
                }
            }
            return count;
        } finally {
            reader.close();
        }
    }

    private static int pageCount(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getNumberOfPages();
        } finally {
            reader.close();
        }
    }

    private static String textOf(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            // Prelom retka u PDF-u je slučajnost rasporeda, ne sadržaja.
            return sb.toString().replaceAll("\\s+", " ");
        } finally {
            reader.close();
        }
    }

    private static RnDetailDto detail() {
        return detail(RnStatus.SUSPENDED);
    }

    private static RnDetailDto detail(RnStatus status) {
        return new RnDetailDto(RN, status,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), null,
                LocalDate.of(2026, 8, 15), null, null, UUID.randomUUID(),
                UUID.randomUUID(), "Grad Zagreb", "Zagreb", null, "Ilica", "1",
                "Apartman Sunce", "Apartman", 4, "3*",
                UUID.randomUUID(), "Ana", "Anić", null, "ana@example.com", "98765432109",
                false, null, null, null,
                null, null, null, null, null);
    }

    /** Sve neobavezno je null: non-EU iznajmljivač bez OIB-a, bez naziva objekta, bez roka. */
    private static RnDetailDto sparseDetail() {
        return new RnDetailDto(RN, RnStatus.SUSPENDED,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null);
    }

    private static LessorEntity lessor() {
        return LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb",
                "ana@example.com");
    }
}
