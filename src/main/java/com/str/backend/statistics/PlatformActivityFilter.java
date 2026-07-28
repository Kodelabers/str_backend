package com.str.backend.statistics;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * STR-3.2: search criteria for the platform activity report. Bundled into a record because the
 * criteria are mostly same-typed strings — as positional arguments they were one transposition
 * away from a silent bug, and the same set is threaded through the list, both exports and the
 * summary.
 *
 * <p>Spring binds this straight from the query string, so the list and both export endpoints
 * declare one parameter instead of repeating nine {@code @RequestParam} lines each. Unsupplied
 * criteria arrive as {@code null} and switch their clause off.
 *
 * <p>{@code anomaliesOnly} is a {@code Boolean} rather than a primitive because the binder passes
 * {@code null} for an absent parameter, which a primitive cannot accept — every request that
 * omitted the flag failed with 400. The compact constructor folds that {@code null} to
 * {@code false}, so {@link #anomaliesOnly()} never returns null. That guarantee matters
 * downstream: binding a null into the SQL comparison would make the predicate evaluate to NULL
 * and silently drop every row instead of disabling the filter.
 *
 * @param status         frontend status token ({@code aktivan}/{@code suspendiran}/{@code povucen}),
 *                       translated to the DB enum name by {@link PlatformActivityQuery}
 * @param anomaliesOnly  spec §2.10: restricts the result to registration numbers flagged as
 *                       anomalous, using the same predicate that produces the header count
 * @param guestCountry   spec §2.10: guest country of residence, matched against {@code str_rn.guest}
 */
public record PlatformActivityFilter(
        Long platformId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate od,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        String county,
        String municipality,
        String status,
        String q,
        String rn,
        Boolean anomaliesOnly,
        String guestCountry
) {

    public PlatformActivityFilter {
        anomaliesOnly = anomaliesOnly != null && anomaliesOnly;
    }

    /** Unfiltered report — every criterion off. */
    public static PlatformActivityFilter none() {
        return new PlatformActivityFilter(null, null, null, null, null, null, null, null, false, null);
    }
}
