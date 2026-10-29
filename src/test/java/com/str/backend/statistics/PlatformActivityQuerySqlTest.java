package com.str.backend.statistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The activity report SQL is Postgres-specific (JSON_AGG), so it is assembled from string
 * fragments rather than written out once. These tests capture what actually reaches the JDBC
 * template — they catch fragments that fail to join cleanly, and they pin the spec §2.10
 * requirement that the anomaly counter and the anomaly filter use one and the same predicate.
 */
class PlatformActivityQuerySqlTest {

    /** Row bound for cases that are not about the export cap itself. */
    private static final int ANY_LIMIT = 1_000;

    private NamedParameterJdbcTemplate jdbc;
    private PlatformActivityQuery query;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        query = new PlatformActivityQuery(jdbc, new ObjectMapper());
        when(jdbc.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    @Test
    void rowSql_joinsFragmentsWithoutMashingTokens() {
        query.queryAll(PlatformActivityFilter.none(), null, ANY_LIMIT);

        String sql = captureRowSql();
        assertThat(sql)
                .contains("FROM str_rn.accommodation_activity aa")
                .contains("WHERE (:platformId IS NULL")
                .contains("GROUP BY aa.rn")
                .contains("ORDER BY aa.period_from DESC");
        // A missing newline between fragments would produce "AS platformsFROM" / "...))GROUP BY".
        assertThat(sql).doesNotContainPattern("[a-zA-Z0-9_)](FROM|WHERE|GROUP BY|ORDER BY)\\b");
    }

    /** Spec §2.10: the header count and the filter must never diverge. */
    @Test
    void anomalyPredicateIsIdenticalInFilterAndHeaderCount() {
        query.query(PlatformActivityFilter.none(), null, 0, 20);

        String summarySql = captureSummarySql();
        // SUSPENSION_PROPOSED is not an anomaly: the proposal only opens the response deadline,
        // so the registration number still covers the reported activity.
        assertThat(summarySql)
                .contains("COUNT(DISTINCT CASE WHEN rn.status NOT IN ('ACTIVE', 'SUSPENSION_PROPOSED')"
                        + " THEN aa.rn END) AS anomalies")
                .contains("AND (:anomaliesOnly = FALSE OR rn.status NOT IN ('ACTIVE', 'SUSPENSION_PROPOSED'))");

        assertThat(captureRowSql())
                .contains("AND (:anomaliesOnly = FALSE OR rn.status NOT IN ('ACTIVE', 'SUSPENSION_PROPOSED'))");
    }

    /** Registry and activity screens must treat a registration number the same way. */
    @Test
    void registrationNumberIsMatchedPartially() {
        query.queryAll(new PlatformActivityFilter(
                null, null, null, null, null, null, null, "HR0100", false, null, false), null, ANY_LIMIT);

        assertThat(captureRowSql()).contains("AND (:rn IS NULL OR aa.rn ILIKE :rnLike)");
        assertThat(captureParams().getValue("rnLike")).isEqualTo("%HR0100%");
    }

    /** The megasearch has to reach the owner and county it already selects and displays. */
    @Test
    void megasearchCoversOwnerAndCounty() {
        query.queryAll(PlatformActivityFilter.none(), null, ANY_LIMIT);

        assertThat(captureRowSql())
                .contains("a.county ILIKE :qLike")
                .contains("COALESCE(l.first_name,'') || ' ' || COALESCE(l.last_name,'')")
                .contains("COALESCE(l.legal_entity_name,'') ILIKE :qLike");
    }

    /** The export bound belongs in SQL — capping only while writing still fetches everything. */
    @Test
    void exportFetchIsBoundedInSqlAndAsksForOneExtraRow() {
        query.queryAll(PlatformActivityFilter.none(), null, 50_000);

        assertThat(captureRowSql()).endsWith(" LIMIT :limit");
        assertThat(captureParams().getValue("limit")).isEqualTo(50_001);
    }

    @Test
    void guestCountryFilterMatchesAgainstGuestTable() {
        query.queryAll(PlatformActivityFilter.none(), null, ANY_LIMIT);

        assertThat(captureRowSql())
                .contains("SELECT 1 FROM str_rn.guest g")
                .contains("WHERE g.activity_id = aa.activity_id")
                .contains("LOWER(g.country) = LOWER(:guestCountry)");
    }

    @Test
    void filterValuesAreBoundAsParameters() {
        query.queryAll(new PlatformActivityFilter(
                7L, null, null, "  ", null, "suspendiran", null, null, true, "  Njemačka  ", false), null, ANY_LIMIT);

        MapSqlParameterSource params = captureParams();
        assertThat(params.getValue("platformId")).isEqualTo(7L);
        assertThat(params.getValue("anomaliesOnly")).isEqualTo(true);
        assertThat(params.getValue("rnStatus")).isEqualTo("SUSPENDED");
        // Blank criteria must become NULL so the "IS NULL" guards switch the clause off.
        assertThat(params.getValue("county")).isNull();
        assertThat(params.getValue("guestCountry")).isEqualTo("Njemačka");
    }

    /** Opozvan (iznajmljivač) i povučen (službenik) filtriraju se zasebno. */
    @Test
    void revokedAndWithdrawnTokensMapToTheirOwnStatus() {
        query.queryAll(new PlatformActivityFilter(
                null, null, null, null, null, "opozvan", null, null, false, null, false), null, ANY_LIMIT);
        assertThat(captureParams().getValue("rnStatus")).isEqualTo("REVOKED");
    }

    /**
     * „Nevažeći" je skupina (suspendirani, opozvani, povučeni), ne status u bazi. Uspoređivao se
     * s nepostojećim {@code INVALID} i vraćao prazan popis; sad traži iste statuse kao registar
     * ({@code RnRegistryView.INVALID}), vezane kao parametar.
     */
    @Test
    void invalidTokenMatchesEveryStatusInvalidForAdvertising() {
        query.queryAll(new PlatformActivityFilter(
                null, null, null, null, null, "nevažeći", null, null, false, null, false), null, ANY_LIMIT);

        MapSqlParameterSource params = captureParams();
        assertThat(params.getValue("rnStatus")).isEqualTo("INVALID");
        assertThat(params.getValue("invalidStatuses")).isEqualTo(List.of("SUSPENDED", "REVOKED", "WITHDRAWN"));
        assertThat(captureRowSql()).contains("OR (:rnStatus = 'INVALID' AND rn.status IN (:invalidStatuses)))");
    }

    /** Stranica, broj redaka i zaglavlje (anomalije) dijele isti filtar, pa i istu granu „nevažeći". */
    @Test
    void invalidTokenAppliesToCountAndSummaryToo() {
        when(jdbc.queryForObject(any(String.class), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        query.query(new PlatformActivityFilter(
                null, null, null, null, null, "nevažeći", null, null, false, null, false), null, 0, 20);

        ArgumentCaptor<String> count = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(count.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertThat(count.getValue()).contains("rn.status IN (:invalidStatuses)");
        assertThat(captureSummarySql()).contains("rn.status IN (:invalidStatuses)");
    }

    /** Jedan izvor istine s registrom — novi nevažeći status ne smije ispasti samo ovdje. */
    @Test
    void invalidStatusesFollowRegistryView() {
        assertThat(PlatformActivityQuery.INVALID_STATUSES).containsExactlyElementsOf(
                com.str.backend.rn.RnRegistryView.INVALID.statuses().stream().map(Enum::name).toList());
    }

    @Test
    void withdrawnTokenStillMapsToWithdrawn() {
        query.queryAll(new PlatformActivityFilter(
                null, null, null, null, null, "povucen", null, null, false, null, false), null, ANY_LIMIT);
        assertThat(captureParams().getValue("rnStatus")).isEqualTo("WITHDRAWN");
    }

    /**
     * An unrecognised status token used to switch the filter off and return everything, withdrawn
     * registration numbers included. On an oversight screen that silence is worse than an error.
     */
    @Test
    void unknownStatusTokenIsRejected() {
        for (String bad : new String[]{"blabla", "ACTIVE", "Aktivan", "active"}) {
            assertThatThrownBy(() -> query.queryAll(new PlatformActivityFilter(
                    null, null, null, null, null, bad, null, null, false, null, false), null, ANY_LIMIT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.activity.status.invalid");
        }
    }

    /**
     * An absent flag must bind as "off". Binding null into the SQL comparison would make the
     * predicate NULL and drop every row — a filter that silently empties the screen.
     */
    @Test
    void absentAnomalyFlagBindsAsFalseNotNull() {
        query.queryAll(new PlatformActivityFilter(
                null, null, null, null, null, null, null, null, null, null, null), null, ANY_LIMIT);

        assertThat(captureParams().getValue("anomaliesOnly")).isEqualTo(false);
    }

    @Test
    void blankStatusLeavesTheFilterOff() {
        query.queryAll(new PlatformActivityFilter(
                null, null, null, null, null, "  ", null, null, false, null, false), null, ANY_LIMIT);

        assertThat(captureParams().getValue("rnStatus")).isNull();
    }

    /**
     * Opseg županijskog službenika: nazivi njegove županije idu u {@code IN}, a {@code county}
     * iz filtra se zanemaruje — inače bi tuđa županija ispraznila ekran.
     */
    @Test
    void scopeCounties_bindScopeAndDropCountyFromFilter() {
        List<String> scope = List.of("Splitsko-dalmatinska županija", "Splitsko-dalmatinska");
        query.queryAll(new PlatformActivityFilter(
                null, null, null, "Grad Zagreb", null, null, null, null, false, null, false), scope, ANY_LIMIT);

        MapSqlParameterSource params = captureParams();
        assertThat(params.getValue("scopeMode")).isEqualTo("ON");
        assertThat(params.getValue("scopeCounties")).isEqualTo(scope);
        assertThat(params.getValue("county")).isNull();
        assertThat(captureRowSql()).contains("AND (:scopeMode IS NULL OR a.county IN (:scopeCounties))");
    }

    /** Bez opsega (cijela RH) klauzula je isključena, a filtar županije radi kao i prije. */
    @Test
    void withoutScope_scopeModeIsNullAndCountyFilterStays() {
        query.queryAll(new PlatformActivityFilter(
                null, null, null, "Grad Zagreb", null, null, null, null, false, null, false), null, ANY_LIMIT);

        MapSqlParameterSource params = captureParams();
        assertThat(params.getValue("scopeMode")).isNull();
        assertThat(params.getValue("county")).isEqualTo("Grad Zagreb");
        // IN s praznom listom nije valjan SQL ni kad se grana ne koristi.
        assertThat((List<?>) params.getValue("scopeCounties")).isNotEmpty();
    }

    /** Stranica, broj redaka i zaglavlje dijele isti opseg. */
    @Test
    void scopeCounties_applyToCountAndSummaryToo() {
        when(jdbc.queryForObject(any(String.class), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        query.query(PlatformActivityFilter.none(), List.of("Grad Zagreb"), 0, 20);

        ArgumentCaptor<String> count = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> countParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(count.capture(), countParams.capture(), eq(Long.class));
        assertThat(count.getValue()).contains("a.county IN (:scopeCounties)");
        assertThat(countParams.getValue().getValue("scopeMode")).isEqualTo("ON");
        assertThat(captureSummarySql()).contains("a.county IN (:scopeCounties)");
    }

    private String captureRowSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce())
                .query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        return sql.getValue();
    }

    private String captureSummarySql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        return sql.getValue();
    }

    private MapSqlParameterSource captureParams() {
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, atLeastOnce()).query(any(String.class), params.capture(), any(RowMapper.class));
        return (MapSqlParameterSource) params.getValue();
    }

    @Test
    void countSqlWrapsTheRowQuery() {
        query.query(PlatformActivityFilter.none(), null, 0, 20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertThat(sql.getValue()).startsWith("SELECT COUNT(*) FROM (").endsWith(") AS sub");
    }
}
