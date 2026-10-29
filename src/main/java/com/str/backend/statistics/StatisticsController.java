package com.str.backend.statistics;

import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.rn.RnScopeGuard;
import com.str.backend.statistics.dto.StrResponse;
import com.str.backend.statistics.dto.PlatformActivitiesPageDto;
import com.str.backend.statistics.dto.PlatformBreakdownDto;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Statistika za službenike (rola RN_MANAGEMENT, {@code SecurityConfig}). Županijski službenik vidi
 * samo svoju županiju ({@link CountyScopeService}) — na dashboardu, u aktivnostima i u svim
 * izvozima; njegov {@code county} iz zahtjeva se zanemaruje.
 */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService service;
    private final PlatformActivityQuery platformActivityQuery;
    private final StatisticsExportService exportService;
    private final CountyScopeService countyScope;
    private final RnScopeGuard scopeGuard;

    public StatisticsController(StatisticsService service,
                                PlatformActivityQuery platformActivityQuery,
                                StatisticsExportService exportService,
                                CountyScopeService countyScope,
                                RnScopeGuard scopeGuard) {
        this.service = service;
        this.platformActivityQuery = platformActivityQuery;
        this.exportService = exportService;
        this.countyScope = countyScope;
        this.scopeGuard = scopeGuard;
    }

    /** Wireframe §11: STR dashboard — RB counts per county, optionally filtered by issue-date range. */
    @GetMapping("/str")
    public StrResponse str(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        return service.str(from, to, countyScope.scopeFor(authentication));
    }

    /** Wireframe §11: STR PDF export — summary with KPI totals and county breakdown. */
    @GetMapping("/str/export/pdf")
    public ResponseEntity<byte[]> strPdf(Authentication authentication) {
        byte[] pdf = exportService.generateStrPdf(countyScope.scopeFor(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"str-statistika.pdf\"")
                .body(pdf);
    }

    /** Wireframe §11: STR detail CSV export — one row per RN. */
    @GetMapping("/str/detail/csv")
    public ResponseEntity<byte[]> strCsv(Authentication authentication) {
        byte[] csv = exportService.generateStrCsv(countyScope.accommodationCounties(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"str-detaljna-statistika.csv\"")
                .body(csv);
    }

    /** Wireframe §11: STR detail Excel export — one row per RN. */
    @GetMapping("/str/detail/xlsx")
    public ResponseEntity<byte[]> strXlsx(Authentication authentication) {
        byte[] xlsx = exportService.generateStrXlsx(countyScope.accommodationCounties(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"str-detaljna-statistika.xlsx\"")
                .body(xlsx);
    }

    /**
     * Wireframe §12: SDIP dashboard — platform activity report, grouped by (RN × period).
     *
     * <p>STR-3.2 / spec §2.10: {@code anomaliesOnly} backs the clickable anomaly counter in the
     * header, and {@code guestCountry} filters by the guest's country of residence. Both combine
     * with every other criterion, including the platform filter.
     */
    @GetMapping("/platform-activities")
    public PlatformActivitiesPageDto platformActivities(
            PlatformActivityFilter filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        int clampedSize = Math.min(Math.max(1, size), 100);
        return platformActivityQuery.query(filter, countyScope.accommodationCounties(authentication),
                page, clampedSize);
    }

    // TODO(auth/BX0): role-gate platform-activities export (voditelj/admin) kad stignu NIAS role —
    // izvoz sadrži imena vlasnika i adrese, a endpointi su trenutno permitAll.

    /** STR-3.2: platform activity report → Excel (obavezni izvoz). Same filters as the list. */
    @GetMapping("/platform-activities/xlsx")
    public ResponseEntity<byte[]> platformActivitiesXlsx(PlatformActivityFilter filter,
                                                         Authentication authentication) {
        byte[] xlsx = exportService.generatePlatformActivitiesXlsx(filter,
                countyScope.accommodationCounties(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"aktivnosti-platformi.xlsx\"")
                .body(xlsx);
    }

    /** STR-3.2: platform activity report → CSV. Same filters as the list. */
    @GetMapping("/platform-activities/csv")
    public ResponseEntity<byte[]> platformActivitiesCsv(PlatformActivityFilter filter,
                                                        Authentication authentication) {
        byte[] csv = exportService.generatePlatformActivitiesCsv(filter,
                countyScope.accommodationCounties(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"aktivnosti-platformi.csv\"")
                .body(csv);
    }

    /**
     * Accordion: per-platform per-country breakdown for a single (RN × period) row. RB izvan
     * opsega službenika je 404, kao na detalju RB-a.
     */
    @GetMapping("/platform-activities/breakdown")
    public PlatformBreakdownDto platformActivityBreakdown(
            @RequestParam String rn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodTo,
            Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        return platformActivityQuery.breakdown(rn, periodFrom, periodTo);
    }
}
