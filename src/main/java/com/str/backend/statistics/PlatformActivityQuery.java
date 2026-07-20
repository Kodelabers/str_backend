package com.str.backend.statistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.common.Strings;
import com.str.backend.exception.BusinessException;
import com.str.backend.statistics.dto.CountryBreakdownDto;
import com.str.backend.statistics.dto.PlatformActivitiesPageDto;
import com.str.backend.statistics.dto.PlatformActivityRowDto;
import com.str.backend.statistics.dto.PlatformBreakdownDto;
import com.str.backend.statistics.dto.PlatformBreakdownItemDto;
import com.str.backend.statistics.dto.PlatformChipDto;
import com.str.backend.statistics.dto.PlatformSummaryDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native SQL implementation of the SDIP activity dashboard query.
 * Groups accommodation_activity by (rn, period) and aggregates platforms via JSON_AGG.
 * NamedParameterJdbcTemplate is used because JPQL cannot express JSON aggregation.
 */
@Component
class PlatformActivityQuery {

    private static final TypeReference<List<PlatformChipDto>> CHIPS_TYPE = new TypeReference<>() {};

    /**
     * Spec §2.10: what counts as an anomaly ("suspektan" RB). Shared by the header count and the
     * "anomalies only" filter — the spec requires the number in the header to equal the number of
     * registration numbers left after applying the filter, so the two must never drift apart.
     */
    private static final String ANOMALY_PREDICATE = "rn.status <> 'ACTIVE'";

    private static final String FROM_JOINS = """
            FROM str_rn.accommodation_activity aa
            JOIN str_rn.online_platform p ON p.platform_id = aa.platform_id
            JOIN str_rn.registration_number rn ON rn.rn = aa.rn
            JOIN str_rn.accommodation a ON a.accommodation_id = rn.accommodation_id
            LEFT JOIN str_rn.submission s ON s.submission_id = rn.submission_id
            LEFT JOIN str_rn.lessor l ON l.lessor_id = s.lessor_id
            """;

    /**
     * Shared by the page, count and summary queries. The guest-country criterion keeps an activity
     * whenever at least one of its guests came from that country; the aggregated nights/guests then
     * still cover the whole activity, not just that country's share.
     */
    private static final String WHERE_FILTERS =
            "WHERE (:platformId IS NULL OR aa.platform_id = :platformId)\n"
          + "  AND (:od IS NULL OR aa.period_to >= :od)\n"
          + "  AND (:toDate IS NULL OR aa.period_from <= :toDate)\n"
          + "  AND (:county IS NULL OR a.county = :county)\n"
          + "  AND (:rnStatus IS NULL OR rn.status = :rnStatus)\n"
          // Partial match, matching the public registry's `rb` filter — the same field behaving
          // differently on two screens was an accident, not a design decision.
          + "  AND (:rn IS NULL OR aa.rn ILIKE :rnLike)\n"
          // Owner and county are searchable here too, for parity with the registry haystack.
          // COALESCE per part: concatenating a NULL name would null the whole expression and
          // silently drop the row from the search.
          + "  AND (:q IS NULL OR aa.rn ILIKE :qLike\n"
          + "       OR a.street ILIKE :qLike\n"
          + "       OR a.city ILIKE :qLike\n"
          + "       OR a.county ILIKE :qLike\n"
          + "       OR (COALESCE(l.first_name,'') || ' ' || COALESCE(l.last_name,'')) ILIKE :qLike\n"
          + "       OR COALESCE(l.legal_entity_name,'') ILIKE :qLike)\n"
          + "  AND (:anomaliesOnly = FALSE OR " + ANOMALY_PREDICATE + ")\n"
          + "  AND (:guestCountry IS NULL OR EXISTS (\n"
          + "          SELECT 1 FROM str_rn.guest g\n"
          + "          WHERE g.activity_id = aa.activity_id\n"
          + "            AND LOWER(g.country) = LOWER(:guestCountry)))\n";

    private static final String BASE_SQL = """
            SELECT
                aa.rn || '|' || aa.period_from::text || '|' || aa.period_to::text AS id,
                aa.rn AS rb,
                COALESCE(l.first_name || ' ' || l.last_name, '') AS owner_name,
                a.street || ' ' || a.street_number AS address,
                a.city,
                a.county AS county_id,
                a.county AS county_name,
                rn.status AS rn_status,
                aa.period_from,
                aa.period_to,
                SUM(aa.overnight_stays) AS nights,
                SUM(aa.guest_count) AS guests_total,
                MAX(aa.received_at) AS reported_at,
                JSON_AGG(DISTINCT jsonb_build_object(
                    'id', p.platform_id::text,
                    'name', p.name
                )) AS platforms
            """
            + FROM_JOINS
            + WHERE_FILTERS
            + """
            GROUP BY aa.rn, a.street, a.street_number, a.city, a.county,
                     rn.status, l.first_name, l.last_name, aa.period_from, aa.period_to
            ORDER BY aa.period_from DESC, aa.rn
            """;

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM (" + BASE_SQL + ") AS sub";

    private static final String SUMMARY_SQL =
            "SELECT\n"
          + "    COUNT(DISTINCT aa.rn) AS accommodations_with_activities,\n"
          + "    COUNT(DISTINCT aa.platform_id) AS platforms_reporting,\n"
          + "    COALESCE(SUM(aa.overnight_stays), 0) AS total_nights,\n"
          + "    COALESCE(SUM(aa.guest_count), 0) AS total_guests,\n"
          + "    COUNT(DISTINCT CASE WHEN " + ANOMALY_PREDICATE + " THEN aa.rn END) AS anomalies\n"
          + FROM_JOINS
          + WHERE_FILTERS;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PlatformActivityQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    PlatformActivitiesPageDto query(PlatformActivityFilter filter, int page, int size) {
        MapSqlParameterSource params = buildParams(filter);

        Long total = jdbc.queryForObject(COUNT_SQL, params, Long.class);
        long totalElements = total == null ? 0 : total;
        int totalPages = Math.max(1, (int) Math.ceil((double) totalElements / size));
        int clampedPage = Math.min(Math.max(0, page), Math.max(0, totalPages - 1));

        PlatformSummaryDto summary = jdbc.queryForObject(SUMMARY_SQL, params, (rs, rowNum) ->
                new PlatformSummaryDto(
                        rs.getLong("accommodations_with_activities"),
                        rs.getLong("platforms_reporting"),
                        rs.getLong("total_nights"),
                        rs.getLong("total_guests"),
                        rs.getLong("anomalies")
                ));
        if (summary == null) summary = new PlatformSummaryDto(0, 0, 0, 0, 0);

        String pagedSql = BASE_SQL + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", size);
        params.addValue("offset", (long) clampedPage * size);

        List<PlatformActivityRowDto> rows = jdbc.query(pagedSql, params, this::mapRow);

        return new PlatformActivitiesPageDto(rows, totalElements, totalPages, clampedPage, size, summary);
    }

    /**
     * STR-3.2: all matching rows (no paging) for Excel/CSV export. Same filters as {@link #query}.
     *
     * <p>Fetches at most {@code limit + 1} rows so the caller can tell "exactly at the cap" from
     * "over the cap" without a second COUNT round-trip. The bound is applied in SQL on purpose:
     * capping only while writing the file would still materialise the whole result set here, which
     * is the expensive half. Rows are grouped by registration number × reporting period and the
     * retention window is 18 months, so a few thousand registration numbers already produce tens
     * of thousands of rows.
     */
    List<PlatformActivityRowDto> queryAll(PlatformActivityFilter filter, int limit) {
        MapSqlParameterSource params = buildParams(filter);
        params.addValue("limit", limit + 1);
        return jdbc.query(BASE_SQL + " LIMIT :limit", params, this::mapRow);
    }

    private PlatformActivityRowDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        List<PlatformChipDto> chips = parseChips(rs.getString("platforms"));
        Timestamp ts = rs.getTimestamp("reported_at");
        return new PlatformActivityRowDto(
                rs.getString("id"),
                rs.getString("rb"),
                rs.getString("owner_name"),
                rs.getString("address"),
                rs.getString("city"),
                rs.getString("county_id"),
                rs.getString("county_name"),
                chips,
                rs.getObject("period_from", LocalDate.class),
                rs.getObject("period_to", LocalDate.class),
                rs.getLong("nights"),
                rs.getLong("guests_total"),
                mapStatus(rs.getString("rn_status")),
                ts != null ? ts.toInstant() : null
        );
    }

    private MapSqlParameterSource buildParams(PlatformActivityFilter f) {
        String q = f.q();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("platformId", f.platformId(), Types.BIGINT);
        p.addValue("od", f.od(), Types.DATE);
        p.addValue("toDate", f.toDate(), Types.DATE);
        p.addValue("county", Strings.blankToNull(f.county()), Types.VARCHAR);
        p.addValue("rnStatus", mapToDbStatus(f.status()), Types.VARCHAR);
        String rn = Strings.blankToNull(f.rn());
        p.addValue("rn", rn, Types.VARCHAR);
        p.addValue("rnLike", rn != null ? "%" + rn + "%" : null, Types.VARCHAR);
        p.addValue("q", Strings.blankToNull(q), Types.VARCHAR);
        p.addValue("qLike", q != null && !q.isBlank() ? "%" + q.trim() + "%" : null, Types.VARCHAR);
        p.addValue("anomaliesOnly", f.anomaliesOnly(), Types.BOOLEAN);
        p.addValue("guestCountry", Strings.blankToNull(f.guestCountry()), Types.VARCHAR);
        return p;
    }

    private List<PlatformChipDto> parseChips(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, CHIPS_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String mapStatus(String dbStatus) {
        if (dbStatus == null) return "bez_rb";
        return switch (dbStatus) {
            case "ACTIVE" -> "aktivan";
            case "SUSPENDED" -> "suspendiran";
            case "WITHDRAWN" -> "povucen";
            default -> "bez_rb";
        };
    }

    /** Frontend sends 'aktivan'/'suspendiran'/'povucen'; map to DB enum names. */
    private static String mapToDbStatus(String frontendStatus) {
        String token = Strings.blankToNull(frontendStatus);
        if (token == null) return null;
        return switch (token) {
            case "aktivan" -> "ACTIVE";
            case "suspendiran" -> "SUSPENDED";
            case "povucen" -> "WITHDRAWN";
            // Rejecting the unknown token matters: it used to disable the filter silently, so a
            // typo — or the DB enum name ("ACTIVE") instead of the UI token — returned every row,
            // withdrawn ones included, on a screen whose whole purpose is to be filtered.
            default -> throw new BusinessException("error.activity.status.invalid");
        };
    }

    // ── Per-row accordion breakdown (RB × period → platform × country) ──────────

    /** Platform totals for the given (rn, period). One row per platform. */
    private static final String BREAKDOWN_PLATFORM_SQL = """
            SELECT
                p.platform_id::text AS platform_id,
                p.name              AS platform_name,
                SUM(aa.overnight_stays) AS platform_nights,
                SUM(aa.guest_count)     AS platform_guests
            FROM str_rn.accommodation_activity aa
            JOIN str_rn.online_platform p ON p.platform_id = aa.platform_id
            WHERE aa.rn = :rn
              AND aa.period_from = :periodFrom
              AND aa.period_to   = :periodTo
            GROUP BY p.platform_id, p.name
            ORDER BY p.name
            """;

    /** Per-platform per-country guest counts for the given (rn, period). */
    private static final String BREAKDOWN_COUNTRY_SQL = """
            SELECT
                aa.platform_id::text AS platform_id,
                g.country            AS country,
                SUM(g.guest_count)   AS country_guests
            FROM str_rn.accommodation_activity aa
            JOIN str_rn.guest g ON g.activity_id = aa.activity_id
            WHERE aa.rn = :rn
              AND aa.period_from = :periodFrom
              AND aa.period_to   = :periodTo
            GROUP BY aa.platform_id, g.country
            """;

    /**
     * Per-row breakdown for the accordion panel. Nights per country are derived
     * proportionally from the per-platform guest share (the guest table only
     * stores guest counts per country, not nights).
     */
    PlatformBreakdownDto breakdown(String rn, LocalDate periodFrom, LocalDate periodTo) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("rn", rn, Types.VARCHAR)
                .addValue("periodFrom", periodFrom, Types.DATE)
                .addValue("periodTo", periodTo, Types.DATE);

        record PlatformAgg(String id, String name, long nights, long guests) {}
        record CountryAgg(String platformId, String country, long guests) {}

        List<PlatformAgg> platformRows = jdbc.query(BREAKDOWN_PLATFORM_SQL, params, (rs, rowNum) ->
                new PlatformAgg(
                        rs.getString("platform_id"),
                        rs.getString("platform_name"),
                        rs.getLong("platform_nights"),
                        rs.getLong("platform_guests")
                ));

        if (platformRows.isEmpty()) {
            return new PlatformBreakdownDto(rn, 0L, 0L, List.of());
        }

        List<CountryAgg> countryRows = jdbc.query(BREAKDOWN_COUNTRY_SQL, params, (rs, rowNum) ->
                new CountryAgg(
                        rs.getString("platform_id"),
                        rs.getString("country"),
                        rs.getLong("country_guests")
                ));

        Map<String, List<CountryAgg>> countriesByPlatform = new LinkedHashMap<>();
        for (CountryAgg c : countryRows) {
            countriesByPlatform.computeIfAbsent(c.platformId(), k -> new ArrayList<>()).add(c);
        }

        long totalNights = 0;
        long totalGuests = 0;
        for (PlatformAgg p : platformRows) {
            totalNights += p.nights();
            totalGuests += p.guests();
        }

        List<PlatformBreakdownItemDto> platforms = new ArrayList<>(platformRows.size());
        for (PlatformAgg p : platformRows) {
            List<CountryAgg> raw = countriesByPlatform.getOrDefault(p.id(), List.of());
            List<CountryBreakdownDto> countries = new ArrayList<>(raw.size());

            // Distribute platform nights proportionally to guest shares. Round
            // each country to the nearest int; any drift goes to the largest bucket.
            long nightsAllocated = 0;
            CountryBreakdownDto largest = null;
            long largestGuests = -1;

            for (CountryAgg c : raw) {
                double shareOfPlatform = p.guests() == 0 ? 0d : (double) c.guests() / p.guests();
                long countryNights = Math.round(p.nights() * shareOfPlatform);
                nightsAllocated += countryNights;
                CountryBreakdownDto dto = new CountryBreakdownDto(
                        c.country(),
                        countryNights,
                        c.guests(),
                        roundOne(shareOfPlatform * 100d)
                );
                countries.add(dto);
                if (c.guests() > largestGuests) {
                    largestGuests = c.guests();
                    largest = dto;
                }
            }

            // Rounding drift: top-up the biggest country so platform total reconciles.
            long drift = p.nights() - nightsAllocated;
            if (drift != 0 && largest != null) {
                int idx = countries.indexOf(largest);
                countries.set(idx, new CountryBreakdownDto(
                        largest.country(),
                        largest.nights() + drift,
                        largest.guests(),
                        largest.sharePercent()
                ));
            }

            countries.sort(Comparator.comparingLong(CountryBreakdownDto::guests).reversed());

            double platformShare = totalNights == 0 ? 0d
                    : roundOne((double) p.nights() / totalNights * 100d);

            platforms.add(new PlatformBreakdownItemDto(
                    p.id(), p.name(), p.nights(), p.guests(), platformShare, countries
            ));
        }

        return new PlatformBreakdownDto(rn, totalNights, totalGuests, platforms);
    }

    private static double roundOne(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
