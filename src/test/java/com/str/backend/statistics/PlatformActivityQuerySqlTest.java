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
        query.queryAll(PlatformActivityFilter.none(), ANY_LIMIT);

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
        query.query(PlatformActivityFilter.none(), 0, 20);

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
                null, null, null, null, null, null, null, "HR0100", false, null, false), ANY_LIMIT);

        assertThat(captureRowSql()).contains("AND (:rn IS NULL OR aa.rn ILIKE :rnLike)");
        assertThat(captureParams().getValue("rnLike")).isEqualTo("%HR0100%");
    }

    /** The megasearch has to reach the owner and county it already selects and displays. */
    @Test
    void megasearchCoversOwnerAndCounty() {
        query.queryAll(PlatformActivityFilter.none(), ANY_LIMIT);

        assertThat(captureRowSql())
                .contains("a.county ILIKE :qLike")
                .contains("COALESCE(l.first_name,'') || ' ' || COALESCE(l.last_name,'')")
                .contains("COALESCE(l.legal_entity_name,'') ILIKE :qLike");
    }

    /** The export bound belongs in SQL — capping only while writing still fetches everything. */
    @Test
    void exportFetchIsBoundedInSqlAndAsksForOneExtraRow() {
        query.queryAll(PlatformActivityFilter.none(), 50_000);

        assertThat(captureRowSql()).endsWith(" LIMIT :limit");
        assertThat(captureParams().getValue("limit")).isEqualTo(50_001);
    }

    @Test
    void guestCountryFilterMatchesAgainstGuestTable() {
        query.queryAll(PlatformActivityFilter.none(), ANY_LIMIT);

        assertThat(captureRowSql())
                .contains("SELECT 1 FROM str_rn.guest g")
                .contains("WHERE g.activity_id = aa.activity_id")
                .contains("LOWER(g.country) = LOWER(:guestCountry)");
    }

    @Test
    void filterValuesAreBoundAsParameters() {
        query.queryAll(new PlatformActivityFilter(
                7L, null, null, "  ", null, "suspendiran", null, null, true, "  Njemačka  ", false), ANY_LIMIT);

        MapSqlParameterSource params = captureParams();
        assertThat(params.getValue("platformId")).isEqualTo(7L);
        assertThat(params.getValue("anomaliesOnly")).isEqualTo(true);
        assertThat(params.getValue("rnStatus")).isEqualTo("SUSPENDED");
        // Blank criteria must become NULL so the "IS NULL" guards switch the clause off.
        assertThat(params.getValue("county")).isNull();
        assertThat(params.getValue("guestCountry")).isEqualTo("Njemačka");
    }

    /**
     * An unrecognised status token used to switch the filter off and return everything, withdrawn
     * registration numbers included. On an oversight screen that silence is worse than an error.
     */
    @Test
    void unknownStatusTokenIsRejected() {
        for (String bad : new String[]{"blabla", "ACTIVE", "Aktivan", "active"}) {
            assertThatThrownBy(() -> query.queryAll(new PlatformActivityFilter(
                    null, null, null, null, null, bad, null, null, false, null, false), ANY_LIMIT))
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
                null, null, null, null, null, null, null, null, null, null, null), ANY_LIMIT);

        assertThat(captureParams().getValue("anomaliesOnly")).isEqualTo(false);
    }

    @Test
    void blankStatusLeavesTheFilterOff() {
        query.queryAll(new PlatformActivityFilter(
                null, null, null, null, null, "  ", null, null, false, null, false), ANY_LIMIT);

        assertThat(captureParams().getValue("rnStatus")).isNull();
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
        query.query(PlatformActivityFilter.none(), 0, 20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertThat(sql.getValue()).startsWith("SELECT COUNT(*) FROM (").endsWith(") AS sub");
    }
}
