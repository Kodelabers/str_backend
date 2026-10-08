package com.str.backend.address;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sužavanje poštanskih brojeva na županiju naselja. Format {@code postanski_brojevi.zupanija}
 * na stvarnoj bazi nije provjeren, pa se ovdje pokrivaju varijante koje ne smiju razbiti
 * usporedbu, i povratak na sve brojeve kad se ništa ne poklopi (samo za popis naselja; broj
 * podnositelja iz druge županije se ne uzima).
 */
class PostalCodesTest {

    private record Row(String getPostalCode, String getPostalCounty, String getCounty)
            implements PostalCodes.Candidate {
    }

    private static List<String> codes(List<Row> rows) {
        return rows.stream().map(Row::getPostalCode).toList();
    }

    @Test
    void brezovicaInGradZagreb_keepsOnlyZagrebCode() {
        List<Row> rows = List.of(
                new Row("10257", "Grad Zagreb", "Grad Zagreb"),
                new Row("31542", "Osječko-baranjska županija", "Grad Zagreb"),
                new Row("33411", "Virovitičko-podravska županija", "Grad Zagreb"));

        assertThat(codes(PostalCodes.preferSameCounty(rows))).containsExactly("10257");
        assertThat(PostalCodes.single(rows)).isEqualTo("10257");
    }

    @Test
    void countyComparison_ignoresCaseSuffixAndGradPrefix() {
        assertThat(PostalCodes.countyKey("GRAD ZAGREB")).isEqualTo(PostalCodes.countyKey("Grad Zagreb"));
        assertThat(PostalCodes.countyKey("Zagreb")).isEqualTo(PostalCodes.countyKey("Grad Zagreb"));
        assertThat(PostalCodes.countyKey("SPLITSKO-DALMATINSKA ŽUPANIJA"))
                .isEqualTo(PostalCodes.countyKey("Splitsko-dalmatinska"));
        assertThat(PostalCodes.countyKey("  Splitsko-dalmatinska   županija "))
                .isEqualTo(PostalCodes.countyKey("Splitsko-dalmatinska županija"));
        // Zagrebačka županija nije Grad Zagreb
        assertThat(PostalCodes.countyKey("Zagrebačka županija")).isNotEqualTo(PostalCodes.countyKey("Grad Zagreb"));
    }

    @Test
    void matchesAcrossFormats() {
        List<Row> rows = List.of(
                new Row("21000", "SPLITSKO-DALMATINSKA", "Splitsko-dalmatinska županija"),
                new Row("10000", "GRAD ZAGREB", "Splitsko-dalmatinska županija"));

        assertThat(codes(PostalCodes.preferSameCounty(rows))).containsExactly("21000");
    }

    @Test
    void noRowMatchesCounty_fallsBackToAllRows() {
        List<Row> rows = List.of(
                new Row("10250", "Zagrebačka županija", "Grad Zagreb"),
                new Row("10251", "Sisačko-moslavačka županija", "Grad Zagreb"));

        assertThat(PostalCodes.preferSameCounty(rows)).isEqualTo(rows);
        assertThat(PostalCodes.single(rows)).isNull();
    }

    /** Popis naselja zadrži jedini broj, a podnositelj tuđi broj ne dobiva. */
    @Test
    void singleRowFromOtherCounty_keptInListButNotForLessor() {
        List<Row> rows = List.of(new Row("21000", "nepoznato", "Splitsko-dalmatinska županija"));

        assertThat(PostalCodes.preferSameCounty(rows)).isEqualTo(rows);
        assertThat(PostalCodes.single(rows)).isNull();
    }

    @Test
    void single_rowWithoutCounty_counts() {
        List<Row> rows = List.of(
                new Row("21000", null, "Splitsko-dalmatinska županija"),
                new Row("10000", "Grad Zagreb", "Splitsko-dalmatinska županija"));

        assertThat(PostalCodes.single(rows)).isEqualTo("21000");
    }

    @Test
    void rowWithoutCounty_isKeptNextToMatchingRow() {
        List<Row> rows = List.of(
                new Row("10257", "Grad Zagreb", "Grad Zagreb"),
                new Row("10258", null, "Grad Zagreb"),
                new Row("31542", "Osječko-baranjska županija", "Grad Zagreb"));

        assertThat(codes(PostalCodes.preferSameCounty(rows))).containsExactly("10257", "10258");
    }

    @Test
    void settlementWithoutPostalCode_staysAsIs() {
        List<Row> rows = List.of(new Row(null, null, "Grad Zagreb"));

        assertThat(PostalCodes.preferSameCounty(rows)).isEqualTo(rows);
        assertThat(PostalCodes.single(rows)).isNull();
    }

    @Test
    void single_sameCodeTwice_isThatCode() {
        List<Row> rows = List.of(
                new Row("10000", "Grad Zagreb", "Grad Zagreb"),
                new Row("10000 ", "Grad Zagreb", "Grad Zagreb"));

        assertThat(PostalCodes.single(rows)).isEqualTo("10000");
    }

    @Test
    void single_noRows_isNull() {
        assertThat(PostalCodes.single(List.<Row>of())).isNull();
    }
}
