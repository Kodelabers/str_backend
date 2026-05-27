package com.str.backend.statistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.statistics.dto.PlatformActivitiesPageDto;
import com.str.backend.statistics.dto.PlatformActivityRowDto;
import com.str.backend.statistics.dto.PlatformChipDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * Native SQL implementation of the SDIP activity dashboard query.
 * Groups accommodation_activity by (rn, period) and aggregates platforms via JSON_AGG.
 * NamedParameterJdbcTemplate is used because JPQL cannot express JSON aggregation.
 */
@Component
class PlatformActivityQuery {

    private static final TypeReference<List<PlatformChipDto>> CHIPS_TYPE = new TypeReference<>() {};

    private static final String BASE_SQL = """
            SELECT
                aa.rn || '|' || aa.period_from || '|' || aa.period_to AS id,
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
                ROUND(SUM(aa.guest_count)::numeric / NULLIF(SUM(aa.overnight_stays), 0), 1) AS avg_guests,
                MAX(aa.received_at) AS reported_at,
                JSON_AGG(DISTINCT jsonb_build_object(
                    'id', p.platform_id::text,
                    'name', p.name
                )) AS platforms
            FROM str_rn.accommodation_activity aa
            JOIN str_rn.online_platform p ON p.platform_id = aa.platform_id
            JOIN str_rn.registration_number rn ON rn.rn = aa.rn
            JOIN str_rn.accommodation a ON a.accommodation_id = rn.accommodation_id
            LEFT JOIN str_rn.submission s ON s.submission_id = rn.submission_id
            LEFT JOIN str_rn.lessor l ON l.lessor_id = s.lessor_id
            WHERE (:platformId IS NULL OR aa.platform_id = :platformId)
              AND (:od IS NULL OR aa.period_to >= :od)
              AND (:toDate IS NULL OR aa.period_from <= :toDate)
              AND (:county IS NULL OR a.county = :county)
              AND (:rnStatus IS NULL OR rn.status = :rnStatus)
              AND (:q IS NULL OR aa.rn ILIKE :qLike OR a.street ILIKE :qLike OR a.city ILIKE :qLike)
            GROUP BY aa.rn, a.street, a.street_number, a.city, a.county,
                     rn.status, l.first_name, l.last_name, aa.period_from, aa.period_to
            ORDER BY aa.period_from DESC, aa.rn
            """;

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM (" + BASE_SQL + ") AS sub";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PlatformActivityQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    PlatformActivitiesPageDto query(Long platformId, LocalDate od, LocalDate toDate,
                                    String county, String rnStatus, String q,
                                    int page, int size) {
        MapSqlParameterSource params = buildParams(platformId, od, toDate, county, rnStatus, q);

        Long total = jdbc.queryForObject(COUNT_SQL, params, Long.class);
        long totalElements = total == null ? 0 : total;
        int totalPages = Math.max(1, (int) Math.ceil((double) totalElements / size));
        int clampedPage = Math.min(Math.max(0, page), Math.max(0, totalPages - 1));

        String pagedSql = BASE_SQL + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", size);
        params.addValue("offset", (long) clampedPage * size);

        List<PlatformActivityRowDto> rows = jdbc.query(pagedSql, params, (rs, rowNum) -> {
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
                    rs.getDouble("avg_guests"),
                    mapStatus(rs.getString("rn_status")),
                    ts != null ? ts.toInstant() : null
            );
        });

        return new PlatformActivitiesPageDto(rows, totalElements, totalPages, clampedPage, size);
    }

    private MapSqlParameterSource buildParams(Long platformId, LocalDate od, LocalDate toDate,
                                              String county, String rnStatus, String q) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("platformId", platformId);
        p.addValue("od", od);
        p.addValue("toDate", toDate);
        p.addValue("county", blankToNull(county));
        p.addValue("rnStatus", mapToDbStatus(rnStatus));
        p.addValue("q", blankToNull(q));
        p.addValue("qLike", q != null && !q.isBlank() ? "%" + q.trim() + "%" : null);
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
        if (frontendStatus == null || frontendStatus.isBlank()) return null;
        return switch (frontendStatus) {
            case "aktivan" -> "ACTIVE";
            case "suspendiran" -> "SUSPENDED";
            case "povucen" -> "WITHDRAWN";
            default -> null;
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
