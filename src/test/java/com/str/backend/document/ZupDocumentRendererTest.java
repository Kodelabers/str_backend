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
}
