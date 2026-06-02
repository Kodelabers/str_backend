package com.str.backend.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTokensTest {

    @Test
    void split_returnsEmpty_forNullBlankOrDelimitersOnly() {
        assertThat(SearchTokens.split(null)).isEmpty();
        assertThat(SearchTokens.split("")).isEmpty();
        assertThat(SearchTokens.split("   ")).isEmpty();
        assertThat(SearchTokens.split(" , , ")).isEmpty();
    }

    @Test
    void split_lowercasesAndSplitsOnWhitespaceAndCommas() {
        assertThat(SearchTokens.split("Korzo 2, Rijeka"))
                .containsExactly("korzo", "2", "rijeka");
    }

    @Test
    void split_collapsesRunsOfDelimitersAndTrims() {
        assertThat(SearchTokens.split("  korzo,,  2 \t rijeka  "))
                .containsExactly("korzo", "2", "rijeka");
    }

    @Test
    void split_capsAtMaxTokens() {
        String many = "t1 t2 t3 t4 t5 t6 t7 t8 t9 t10 t11 t12";
        List<String> tokens = SearchTokens.split(many);
        assertThat(tokens).hasSize(SearchTokens.MAX_TOKENS);
        assertThat(tokens).containsExactly("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10");
    }

    @Test
    void slots_alwaysReturnsMaxTokensLength_withTrailingNulls() {
        String[] slots = SearchTokens.slots("korzo 2");
        assertThat(slots).hasSize(SearchTokens.MAX_TOKENS);
        assertThat(slots[0]).isEqualTo("korzo");
        assertThat(slots[1]).isEqualTo("2");
        assertThat(slots[2]).isNull();
        assertThat(slots[SearchTokens.MAX_TOKENS - 1]).isNull();
    }

    @Test
    void slots_allNull_forEmptyQuery() {
        assertThat(SearchTokens.slots(null)).containsOnlyNulls().hasSize(SearchTokens.MAX_TOKENS);
        assertThat(SearchTokens.slots("  ")).containsOnlyNulls().hasSize(SearchTokens.MAX_TOKENS);
    }
}
