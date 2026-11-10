package com.str.backend.pdf;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.document.DocumentProperties;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registries.eovlastenja.LegalForm;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zahtjev u ime tvrtke (e-Zastupanja): podnositelj je tvrtka s <b>njezinim</b> OIB-om, a zastupnik
 * je u zasebnom retku. Ranije je u retku „OIB" pravne osobe stajao OIB zastupnika.
 */
class SubmissionPdfLegalEntityTest {

    private static final DocumentProperties PROPS = new DocumentProperties(
            new DocumentProperties.Tijelo("Ministarstvo turizma i sporta", "87892589782",
                    "Prisavlje 14", "Zagreb", "Uprava za turizam", null),
            new DocumentProperties.Potpisnik(null, null),
            null, Map.of(), false, false, Map.of());

    @Test
    void legalEntity_printsCompanyOib_andRepresentativeSeparately() throws Exception {
        LessorEntity lessor = LessorEntity.create("Ana", "Horvat", "", "", "", "", "ana@example.com");
        lessor.setLessorOib("33333333360");
        lessor.applyNiasLegalEntity("TESTNA TVRTKA d.o.o.", null, "70000000004", "Ana Horvat", null);
        AccommodationEntity accommodation = AccommodationEntity.create(null, "Grad Zagreb", "Zagreb",
                "Ilica", "1", 4, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, true);

        byte[] pdf = new SubmissionPdfGenerator(PROPS).generate(
                SubmissionPdfContext.of(accommodation, lessor, "Hoteli", "Hoteli", null, "HR120001000000000123", null));

        String text = textOf(pdf);
        assertThat(text).contains("TESTNA TVRTKA d.o.o.").contains("Pravna osoba");
        assertThat(text).contains("OIB 33333333360");
        assertThat(text).contains("Ana Horvat, OIB: 70000000004");
    }

    /** T13: OPG i obrt iz e-Ovlaštenja imaju svoj pravni oblik, a skupina je ona iz šifrarnika. */
    @Test
    void familyFarm_printsItsLegalForm_andGroup() throws Exception {
        LessorEntity lessor = LessorEntity.create("Ana", "Horvat", "", "", "", "", "ana@example.com");
        lessor.setLessorOib("55555555551");
        lessor.applyNiasLegalEntity("OPG TESTNI", null, "70000000004", "Ana Horvat", null);
        lessor.applyLegalForm(LegalForm.OPG);
        AccommodationEntity accommodation = AccommodationEntity.create(null, "Grad Zagreb", "Zagreb",
                "Ilica", "1", 4, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, true);

        String text = textOf(new SubmissionPdfGenerator(PROPS).generate(SubmissionPdfContext.of(accommodation, lessor,
                "Soba", "Objekti u kojima se pružaju ugostiteljske usluge u obiteljskom poljoprivrednom gospodarstvu",
                null, "HR120001000000000124", null)));

        assertThat(text).contains("Obiteljsko poljoprivredno gospodarstvo").doesNotContain("Pravna osoba");
        assertThat(text).contains("obiteljskom poljoprivrednom gospodarstvu");
    }

    @Test
    void craft_printsObrt() throws Exception {
        LessorEntity lessor = LessorEntity.create("Ana", "Horvat", "", "", "", "", "ana@example.com");
        lessor.setLessorOib("44444444446");
        lessor.applyNiasLegalEntity("TESTNI OBRT", null, "70000000004", "Ana Horvat", null);
        lessor.applyLegalForm(LegalForm.OBRT);
        AccommodationEntity accommodation = AccommodationEntity.create(null, "Grad Zagreb", "Zagreb",
                "Ilica", "1", 4, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, true);

        String text = textOf(new SubmissionPdfGenerator(PROPS).generate(SubmissionPdfContext.of(accommodation, lessor,
                "Hoteli", "Hoteli", null, "HR120001000000000125", null)));

        assertThat(text).contains("Obrt").doesNotContain("Pravna osoba"); // naziv obrta je velikim slovima
    }

    private static String textOf(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString().replaceAll("\\s+", " ");
        } finally {
            reader.close();
        }
    }
}
