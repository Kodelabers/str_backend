package com.str.backend.document;

import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
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
    private StrDocumentService service;

    private static final DocumentProperties PROPERTIES = new DocumentProperties(
            new DocumentProperties.Tijelo("Ministarstvo turizma i sporta", "12345678901",
                    "Prisavlje 14", "Zagreb", "Uprava za turizam",
                    "članka 6. Uredbe (EU) 2024/1028"),
            new DocumentProperties.Potpisnik("Ivana Ivić", "Voditeljica postupka"),
            new DocumentProperties.Epecat(false, null, null, null, null),
            Map.of("suspenzija", "može se pokrenuti upravni spor u roku od 30 dana."),
            false);

    private static final DocumentProperties PROPERTIES_S_PECATOM = new DocumentProperties(
            PROPERTIES.tijelo(), PROPERTIES.potpisnik(),
            new DocumentProperties.Epecat(true, "CN=Fina RDC 2020,O=Financijska agencija,C=HR",
                    "CN=KVALIFICIRANI ELEKTRONIČKI PEČAT MINISTARSTVA TURIZMA I SPORTA",
                    "SHA256withRSA", "https://provjera.example.hr/"),
            PROPERTIES.uputa(), false);

    static Set<StrDocumentType> templateBackedTypes() {
        return StrDocumentType.templateBackedTypes();
    }

    /** Akti koje izdaje tijelo — podnesak stranke (prigovor) nema okvir tijela. */
    static Stream<StrDocumentType> aktiTijela() {
        return StrDocumentType.templateBackedTypes().stream()
                .filter(t -> t.smjer() == StrDocumentType.Smjer.IZLAZNO);
    }

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        lessorRepository = mock(LessorRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        logRepository = mock(RegistrationNumberLogRepository.class);

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
                new ZupDocumentRenderer(properties), labels);
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
        assertThat(text).contains("Obrazloženje");
        assertThat(text).contains("službenoj dužnosti");
        assertThat(text).contains("Anić");
        assertThat(text).contains("važeći");
        assertThat(text).contains("kratkoročni najam");
    }

    /** Čl. 98. st. 1 i 6 — akt koji dira u prava stranke mora nositi uputu o pravnom lijeku. */
    @Test
    void suspensionAct_carriesConfiguredLegalRemedy() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "razlog"));

        assertThat(text).contains("Uputa o pravnom lijeku");
        assertThat(text).contains("upravni spor u roku od 30 dana");
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
        assertThat(text.indexOf("1. Suspendira se")).isLessThan(text.indexOf("Obrazloženje"));
        assertThat(text.indexOf("Obrazloženje")).isLessThan(text.indexOf("Uputa o pravnom lijeku"));
        // Naziv tijela velikim slovima je i u zaglavlju, pa potpis traži zadnje pojavljivanje.
        int potpis = text.lastIndexOf("MINISTARSTVO TURIZMA I SPORTA");
        assertThat(text.indexOf("Uputa o pravnom lijeku")).isLessThan(potpis);
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

        assertThat(text).contains("najkasnije do 15. kolovoza 2026.");
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

        assertThat(text).contains("Obustavlja se postupak suspenzije");
        assertThat(text).contains("ostaje važeći");
        assertThat(text).contains("dokumentacija je dopunjena");
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
                PROPERTIES.uputa(), false);

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

        assertThat(text).contains("Ministarstvo turizma i sporta, OIB: 12345678901, na temelju"
                + " članka 6. Uredbe (EU) 2024/1028, na zahtjev Ana Anić, OIB: 98765432109, izdaje");
        assertThat(text).contains("OBAVIJEST O DODJELI REGISTRACIJSKOG BROJA");
        assertThat(text).contains("1. Smještajnoj jedinici „Apartman Sunce\", na adresi Ilica 1,"
                + " Zagreb, skupine: Objekti u kojima se pružaju ugostiteljske usluge u domaćinstvu,"
                + " vrste: Apartman, dodijeljen je registracijski broj " + RN + ".");
        assertThat(text).contains("2. Registracijski broj upisan je u integrirani informacijski"
                + " sustav turizma dana 01. ožujka 2026. godine.");
        assertThat(text).doesNotContain("I Z R E K A");
        assertThat(text).doesNotContain("Prilozi");
        assertThat(text).doesNotContain("Dostaviti");
        assertThat(text).doesNotContain("u postupku pokrenutom");
        assertThat(text).doesNotContain("Utvrđuje se");
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
     * Struktura je jedno, pravni sastavni dijelovi drugo: rješenja i dalje nose obrazloženje
     * (čl. 98. st. 5) i uputu o pravnom lijeku (čl. 98. st. 6), koju po čl. 111. nose na štetu
     * tijela ako je izostane.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SUSPENZIJA", "POVLACENJE"})
    void adverseActs_keepReasoningAndLegalRemedy(String slug) throws IOException {
        String text = textOf(service.render(StrDocumentType.valueOf(slug), RN, "razlog"));

        assertThat(text).contains("Obrazloženje");
        assertThat(text).contains("Uputa o pravnom lijeku");
    }

    /** Prijedlog suspenzije je poziv na izjašnjavanje: obrazloženje da, uputa ne. */
    @Test
    void proposalAct_keepsReasoningWithoutLegalRemedy() throws IOException {
        String text = textOf(service.render(StrDocumentType.PRIJEDLOG_SUSPENZIJE, RN, "razlog"));

        assertThat(text).contains("Obrazloženje");
        assertThat(text).doesNotContain("Uputa o pravnom lijeku");
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
        String text = textOf(service.render(StrDocumentType.DODJELA, RN, null,
                new FilingReference("334-06/26-10/1", "529-06-03/01-26-2", 21748084)));

        assertThat(text).contains("P/21748084");
        assertThat(text).contains("KLASA: 334-06/26-10/1 URBROJ: 529-06-03/01-26-2 Zagreb, ");
    }

    @Test
    void header_omitsJop_beforeFiling() throws IOException {
        String text = textOf(service.render(StrDocumentType.DODJELA, RN, null,
                new FilingReference("334-06/26-10/1", null)));

        assertThat(text).doesNotContain("P/");
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

    /**
     * Obustava je iz zadnjeg zapisa ispisivala samu sebe: „pokrenut zbog sljedećeg razloga:
     * obustava postupka suspenzije". Razlog pokretanja postupka je onaj s prijedloga.
     */
    @Test
    void revocationAct_citesGroundFromProposal_notItself() throws IOException {
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, "SUSPENSION_PROPOSED"))
                .thenReturn(Optional.of(RegistrationNumberLogEntity.transition(RN, "ACTIVE",
                        "SUSPENSION_PROPOSED", "INCOMPLETE_DOCUMENTATION", null, null)));
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition(RN, "SUSPENSION_PROPOSED", "ACTIVE",
                        "REVOKE_PROPOSAL", null, null)));

        String text = textOf(service.render(StrDocumentType.OBUSTAVA_SUSPENZIJE, RN, null));

        assertThat(text).contains("pokrenut zbog sljedećeg razloga: nepotpuna dokumentacija");
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

        assertThat(text).contains("Zagreb, " + java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd. MMMM yyyy.",
                        java.util.Locale.forLanguageTag("hr"))));
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
                PROPERTIES.uputa(), false);

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

        assertThat(text).contains("nepotpuna dokumentacija");
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
