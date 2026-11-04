package com.str.backend.document;

import org.junit.jupiter.api.Test;

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
