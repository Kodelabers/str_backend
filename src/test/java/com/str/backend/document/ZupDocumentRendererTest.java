package com.str.backend.document;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZupDocumentRendererTest {

    /**
     * URBROJ prije urudžbiranja je prazan. Redak mu mora nestati, a ne ostati kao prazan redak
     * koji bi urudžbeni blok prelomio u dva odlomka s razmakom između KLASE i datuma.
     */
    @Test
    void emptyLineAfterBinding_isDropped_withoutSplittingParagraph() {
        String izvor = "${a}\n${b}\n${c}";
        Map<String, String> ctx = Map.of("a", "KLASA: 1", "b", "", "c", "Zagreb, 1. rujna 2026.");

        String vezano = ZupDocumentRenderer.vezi(izvor, ctx, "test");

        assertThat(ZupDocumentRenderer.odlomci(vezano, ZupSection.Mode.BLOK))
                .containsExactly("KLASA: 1\nZagreb, 1. rujna 2026.");
    }

    /** Prazni redci iz samog predloška i dalje dijele odlomke. */
    @Test
    void blankTemplateLines_stillSeparateParagraphs() {
        String izvor = "Prvi ${a}\n\n${b}\n\nTreći";
        Map<String, String> ctx = Map.of("a", "odlomak", "b", "");

        String vezano = ZupDocumentRenderer.vezi(izvor, ctx, "test");

        assertThat(ZupDocumentRenderer.odlomci(vezano, ZupSection.Mode.PROZA))
                .containsExactly("Prvi odlomak", "Treći");
    }

    /**
     * Sekcija koja nakon vezanja ostane prazna (neobavezno obrazloženje, UAT 13.11.) ne ispisuje se — ni
     * naslov; s vrijednošću se ispisuje s naslovom.
     */
    @Test
    void sectionBlankAfterBinding_isOmittedWithHeading() throws Exception {
        ZupTemplate predlozak = new ZupTemplate(StrDocumentType.OBUSTAVA_SUSPENZIJE, Map.of(
                ZupSection.UVOD, "Uvod akta",
                ZupSection.IZREKA, "1. Izreka akta",
                ZupSection.OBRAZLOZENJE, "${akt.obrazlozenje}",
                ZupSection.POTPISNIK, "Potpis"));
        ZupDocumentRenderer renderer = noviRenderer();
        Map<String, String> ctx = new HashMap<>(Map.of(
                "tijelo.nazivVelikim", "TIJELO", "tijelo.ustrojstvenaJedinica", "",
                "akt.klasaRedak", "", "akt.urbrojRedak", "", "akt.mjestoDatum", "Zagreb, 1. rujna 2026.",
                "akt.jopRedak", "", "akt.naslov", "OBAVIJEST", "akt.obrazlozenje", " "));

        String bez = tekst(renderer.render(predlozak, ctx));
        ctx.put("akt.obrazlozenje", "Stranka je otklonila nedostatak.");
        String s = tekst(renderer.render(predlozak, ctx));

        assertThat(bez).contains("1. Izreka akta").doesNotContain("Obrazloženje");
        assertThat(s).contains("Obrazloženje").contains("Stranka je otklonila nedostatak.");
    }

    /**
     * Prijelom retka u obrazloženju službenika je granica odlomka ({@link ZupContextFactory#putObrazlozenje}):
     * sekcija je PROZA, pa bi se „1. …" i „2. …" inače spojili u jedan odlomak.
     */
    @Test
    void statementOfReasons_singleLineBreaks_becomeParagraphs() {
        Map<String, String> ctx = new HashMap<>();
        ZupContextFactory.putObrazlozenje(ctx, "  1. Suglasnost je dostavljena.\n2. Nadzor je okončan.  \r\n\n\n\n"
                + "   3. Ostalo.\t");

        String vezano = ZupDocumentRenderer.vezi("${akt.obrazlozenje}", ctx, "test");

        assertThat(ctx.get("akt.obrazlozenje"))
                .isEqualTo("1. Suglasnost je dostavljena.\n\n2. Nadzor je okončan.\n\n3. Ostalo.");
        assertThat(ZupDocumentRenderer.odlomci(vezano, ZupSection.Mode.PROZA)).containsExactly(
                "1. Suglasnost je dostavljena.", "2. Nadzor je okončan.", "3. Ostalo.");
    }

    /**
     * Renderer za testove na jednom mjestu: grana {@code be-uat-izjava} konstruktoru dodaje
     * {@code PecatiranjeAkta}, pa se nakon spajanja prilagođava samo ovo.
     */
    private static ZupDocumentRenderer noviRenderer() {
        return new ZupDocumentRenderer(new DocumentProperties(
                new DocumentProperties.Tijelo("Tijelo", "12345678901", null, "Zagreb", null, "propis"),
                new DocumentProperties.Potpisnik(null, null),
                new DocumentProperties.Epecat(false, null, null, null, null),
                Map.of(), false, false, Map.of()));
    }

    private static String tekst(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1)
                    .replaceAll("\\s+", " ");
        } finally {
            reader.close();
        }
    }

    /**
     * Barkod oznake pismena („Generirati kod kao u eGOP-u", MINTS) mora se dati očitati natrag u
     * istu oznaku koja je ispisana ispod njega — necitljiv barkod gori je od nikakvog.
     */
    @Test
    void barcode_decodesBackToTheFilingMark() throws Exception {
        boolean[] moduli = ZupDocumentRenderer.barkodModuli("P/81591802");
        int tiho = 10;
        com.google.zxing.common.BitArray red = new com.google.zxing.common.BitArray(moduli.length + 2 * tiho);
        for (int i = 0; i < moduli.length; i++) {
            if (moduli[i]) {
                red.set(tiho + i);
            }
        }

        com.google.zxing.Result rezultat = new com.google.zxing.oned.Code93Reader().decodeRow(0, red, Map.of());

        assertThat(rezultat.getText()).isEqualTo("P/81591802");
        assertThat(rezultat.getBarcodeFormat()).isEqualTo(com.google.zxing.BarcodeFormat.CODE_93);
    }
}
