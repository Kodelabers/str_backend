package com.str.backend.pdf;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.document.DocumentProperties;
import com.str.backend.document.FilingReference;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.str.FacilityRooms.Rooms;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF zahtjeva: urudžbene oznake u zasebnim retcima (kao u zaglavlju obavijesti) i tablica
 * kapaciteta s kategorijom i sobama postojećeg objekta.
 */
class SubmissionPdfZahtjevTest {

    private static final String RN = "HR120001000000000123";

    private static final DocumentProperties PROPS = new DocumentProperties(
            new DocumentProperties.Tijelo("ministarstvo turizma i sporta", "87892589782",
                    "Prisavlje 14", "Zagreb", "Uprava za turizam", null),
            new DocumentProperties.Potpisnik(null, null),
            null, Map.of(), false);

    private final SubmissionPdfGenerator generator = new SubmissionPdfGenerator(PROPS);

    @Test
    void klasaIUrbroj_uZasebnimRetcima() throws Exception {
        String text = render(accommodation(), null, new FilingReference("334-06/26-10/1", "529-06-03/01-26-1"));

        assertThat(text).contains("KLASA 334-06/26-10/1");
        assertThat(text).contains("URBROJ 529-06-03/01-26-1");
        assertThat(text).doesNotContain("Urudžbeni broj");
        assertThat(text).doesNotContain("KLASA: ");
    }

    @Test
    void bezUrudzbe_nemaRedakaKlaseNiUrbroja() throws Exception {
        String text = render(accommodation(), null, null);

        assertThat(text).doesNotContain("KLASA").doesNotContain("URBROJ");
    }

    /** Adresat zahtjeva počinje velikim slovom i kad je env upisan malim. */
    @Test
    void adresat_pocinjeVelikimSlovom() throws Exception {
        String text = render(accommodation(), null, null);

        assertThat(text).contains("Ministarstvo turizma i sporta").doesNotContain("ministarstvo turizma");
    }

    @Test
    void postojeciObjekt_kategorijaISobePoVrsti() throws Exception {
        AccommodationEntity a = accommodation();
        a.setCategory("3 zvjezdice");

        String text = render(a, new Rooms(1, 2, null), null);

        // Ekstraktor susjedne ćelije spaja bez razmaka, pa se razmak između njih ne traži.
        assertThat(text).containsPattern("Apartman Sunce\\s*3 zvjezdice\\s*6\\s*3 ");
        assertThat(text).containsPattern("Jednokrevetne sobe\\s*1 ");
        assertThat(text).containsPattern("Dvokrevetne sobe\\s*2 ");
        assertThat(text).containsPattern("Trokrevetne sobe UKUPNO");
        // Ukupno 3 sobe — u gornjem retku i u retku UKUPNO.
        assertThat(text).contains("UKUPNO 1 6 3");
    }

    /** Novi objekt soba nema (obrazac ih ne prikuplja) — stupci ostaju prazni, ne „0" ni „null". */
    @Test
    void noviObjekt_bezSoba_prazniStupci() throws Exception {
        String text = render(accommodation(), null, null);

        assertThat(text).contains("UKUPNO 1 6");
        assertThat(text).doesNotContain("UKUPNO 1 6 0");
        assertThat(text).doesNotContain("null");
    }

    @Test
    void trazenaKategorija_imaPrednostPredPostojecom() throws Exception {
        AccommodationEntity a = accommodation();
        a.setCategory("2 zvjezdice");
        a.setRequestedCategory("4 zvjezdice");

        String text = render(a, null, null);

        assertThat(text).contains("4 zvjezdice").doesNotContain("2 zvjezdice");
    }

    private String render(AccommodationEntity accommodation, Rooms rooms, FilingReference filing) throws Exception {
        LessorEntity lessor = LessorEntity.create("Ana", "Horvat", "Ilica", "1", "Zagreb", "Grad Zagreb",
                "ana@example.com");
        lessor.setLessorOib("33333333360");
        return textOf(generator.generate(SubmissionPdfContext.of(accommodation, lessor, "Apartman", rooms, RN, filing)));
    }

    private static AccommodationEntity accommodation() {
        AccommodationEntity a = AccommodationEntity.create(null, "Grad Zagreb", "Zagreb",
                "Ilica", "1", 6, 6, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, true);
        a.setName("Apartman Sunce");
        return a;
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
