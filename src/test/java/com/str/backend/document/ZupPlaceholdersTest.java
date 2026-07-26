package com.str.backend.document;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZupPlaceholdersTest {

    @Test
    void bind_replacesKnownKeys() {
        String out = ZupPlaceholders.bind("Broj ${rn.broj}, stranka ${stranka.naziv}.",
                Map.of("rn.broj", "HR180000123456789001", "stranka.naziv", "Ana Anić"), "test");

        assertThat(out).isEqualTo("Broj HR180000123456789001, stranka Ana Anić.");
    }

    /**
     * Tipfeler u predlošku mora pasti, a ne tiho ostaviti prazninu — akt bez broja ili bez
     * roka izgleda uredno, a pravno je neispravan.
     */
    @Test
    void bind_unknownKey_throwsInsteadOfBlanking() {
        assertThatThrownBy(() -> ZupPlaceholders.bind("Rok: ${rok.ispravka}", Map.of(), "suspenzija.txt"))
                .isInstanceOf(DocumentTemplateException.class)
                .hasMessageContaining("rok.ispravka")
                .hasMessageContaining("suspenzija.txt");
    }

    /** Prazna vrijednost je legitimna (npr. iznajmljivač bez poštanskog broja). */
    @Test
    void bind_emptyValue_isAllowed() {
        String out = ZupPlaceholders.bind("[${stranka.postanskiBroj}]",
                Map.of("stranka.postanskiBroj", ""), "test");

        assertThat(out).isEqualTo("[]");
    }

    /** Vrijednost s $ ili \\ ne smije se tumačiti kao regex zamjena. */
    @Test
    void bind_valueWithRegexMetacharacters_isLiteral() {
        String out = ZupPlaceholders.bind("${x}", Map.of("x", "MINT\\strservis $1"), "test");

        assertThat(out).isEqualTo("MINT\\strservis $1");
    }

    @Test
    void bind_reportsAllUnknownKeysAtOnce() {
        assertThatThrownBy(() -> ZupPlaceholders.bind("${a.b} ${c.d}", Map.of(), "test"))
                .hasMessageContaining("a.b")
                .hasMessageContaining("c.d");
    }

    @Test
    void keysIn_listsPlaceholders() {
        assertThat(ZupPlaceholders.keysIn("${b} i ${a} pa opet ${a}")).containsExactly("a", "b");
    }
}
