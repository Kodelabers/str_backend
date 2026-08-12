package com.str.backend.document;

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

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
            new DocumentProperties.Epecat(false, "ovjereno pečatom"),
            Map.of("suspenzija", "može se pokrenuti upravni spor u roku od 30 dana."),
            false);

    static Set<StrDocumentType> templateBackedTypes() {
        return StrDocumentType.templateBackedTypes();
    }

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        lessorRepository = mock(LessorRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        logRepository = mock(RegistrationNumberLogRepository.class);

        ZupTemplateLoader loader = new ZupTemplateLoader(PROPERTIES);
        loader.loadAll();
        DocumentLabels labels = new DocumentLabels();

        service = new StrDocumentService(rnRepository, lessorRepository, submissionRepository,
                logRepository, loader, new ZupContextFactory(PROPERTIES, labels),
                new ZupDocumentRenderer(PROPERTIES), labels);

        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(detail()));
        when(lessorRepository.findById(any())).thenReturn(Optional.of(lessor()));
        when(submissionRepository.findById(any())).thenReturn(Optional.empty());
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.empty());
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
        assertThat(text.indexOf("OBAVIJEST O SUSPENZIJI")).isLessThan(text.indexOf("I Z R E K A"));
        assertThat(text.indexOf("I Z R E K A")).isLessThan(text.indexOf("Obrazloženje"));
        assertThat(text.indexOf("Obrazloženje")).isLessThan(text.indexOf("Uputa o pravnom lijeku"));
        assertThat(text.indexOf("Uputa o pravnom lijeku")).isLessThan(text.indexOf("Voditeljica postupka"));
        assertThat(text.indexOf("Voditeljica postupka")).isLessThan(text.indexOf("Dostaviti"));
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

        assertThat(text).contains("najkasnije do 15.08.2026.");
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
    void act_omitsSealClause_whileSealIsDisabled() throws IOException {
        String text = textOf(service.render(StrDocumentType.SUSPENZIJA, RN, "razlog"));

        assertThat(text).doesNotContain("ovjereno pečatom");
        assertThat(text).contains("Voditeljica postupka");
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
