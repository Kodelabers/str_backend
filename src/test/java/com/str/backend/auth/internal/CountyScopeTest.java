package com.str.backend.auth.internal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Usporedba naziva županije i oblik opsega u sesiji. */
class CountyScopeTest {

    private static final CountyScope SPLIT =
            CountyScope.county("Splitsko-dalmatinska županija", 17L, 17, "Splitsko-dalmatinska županija");
    private static final CountyScope ALL = CountyScope.all(CountyScope.Outcome.NONE);

    @Test
    void restricted_onlyWithCounty() {
        assertThat(SPLIT.restricted()).isTrue();
        assertThat(ALL.restricted()).isFalse();
        assertThat(CountyScope.ministry("Ministarstvo turizma i sporta").restricted()).isFalse();
    }

    /** Isti podatak u tri zapisa (adresni registar, lokalni seed, eTurizam) je ista županija. */
    @Test
    void covers_matchesStoredVariants() {
        assertThat(SPLIT.covers("Splitsko-dalmatinska županija")).isTrue();
        assertThat(SPLIT.covers("Splitsko-dalmatinska")).isTrue();
        assertThat(SPLIT.covers("SPLITSKO-DALMATINSKA ŽUPANIJA")).isTrue();
        assertThat(SPLIT.covers("  Splitsko-dalmatinska   županija ")).isTrue();

        assertThat(SPLIT.covers("Grad Zagreb")).isFalse();
        assertThat(SPLIT.covers("Šibensko-kninska županija")).isFalse();
    }

    /** Smještaj bez županije ne pripada nijednoj — županijski službenik ga ne vidi. */
    @Test
    void covers_missingCounty_isOutsideRestrictedScope() {
        assertThat(SPLIT.covers(null)).isFalse();
        assertThat(SPLIT.covers(" ")).isFalse();
        assertThat(ALL.covers(null)).isTrue();
        assertThat(ALL.covers("Grad Zagreb")).isTrue();
    }

    @Test
    void key_isComparableFormOrNull() {
        assertThat(SPLIT.key()).isEqualTo("splitsko-dalmatinska");
        assertThat(ALL.key()).isNull();
    }

    @Test
    void matching_returnsEveryStoredVariantOfTheCounty() {
        List<String> stored = Arrays.asList("Grad Zagreb", "Splitsko-dalmatinska županija", null,
                "SPLITSKO-DALMATINSKA ŽUPANIJA", "Splitsko-dalmatinska", "Splitsko-dalmatinska");

        assertThat(SPLIT.matching(stored)).containsExactly(
                "Splitsko-dalmatinska županija", "SPLITSKO-DALMATINSKA ŽUPANIJA", "Splitsko-dalmatinska");
    }

    /** {@code IN ()} s praznom listom nije valjan SQL — bez pogotka ide sam naziv, koji ne pogađa ništa. */
    @Test
    void matching_isNeverEmpty() {
        assertThat(SPLIT.matching(List.of("Grad Zagreb"))).containsExactly("Splitsko-dalmatinska županija");
        assertThat(SPLIT.matching(List.of())).containsExactly("Splitsko-dalmatinska županija");
    }

    @Test
    void matching_unrestricted_isNull() {
        assertThat(ALL.matching(List.of("Grad Zagreb"))).isNull();
    }

    /**
     * Ishod COUNTY s praznim nazivom nema s čime usporediti — ne ograničava, a usporedba ne smije
     * pasti ({@code key()} je tada {@code null}).
     */
    @Test
    void countyOutcome_blankName_isNotRestricted_andCoversDoesNotThrow() {
        CountyScope blank = CountyScope.county("Splitsko-dalmatinska županija", 17L, 17, "  ");

        assertThat(blank.restricted()).isFalse();
        assertThat(blank.key()).isNull();
        assertThatCode(() -> {
            blank.covers("Grad Zagreb");
            blank.covers(null);
            blank.covers("  ");
        }).doesNotThrowAnyException();
        assertThat(blank.covers("Grad Zagreb")).isTrue();
        assertThat(blank.covers(null)).isTrue();
        assertThat(blank.matching(List.of("Grad Zagreb"))).isNull();
    }

    /** Naziv županije bez ishoda COUNTY (npr. višeznačna nadležnost, greška) ne ograničava. */
    @Test
    void countyNameWithoutCountyOutcome_isNotRestricted() {
        for (CountyScope.Outcome outcome : CountyScope.Outcome.values()) {
            if (outcome == CountyScope.Outcome.COUNTY) {
                continue;
            }
            CountyScope scope = new CountyScope(outcome, "Splitsko-dalmatinska županija", 17L, 17,
                    "Splitsko-dalmatinska županija");

            assertThat(scope.restricted()).as(outcome.name()).isFalse();
            assertThat(scope.key()).as(outcome.name()).isNull();
            assertThat(scope.covers("Grad Zagreb")).as(outcome.name()).isTrue();
            assertThat(scope.matching(List.of("Grad Zagreb"))).as(outcome.name()).isNull();
        }
    }
}
