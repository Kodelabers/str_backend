package com.str.backend.statistics;

import com.str.backend.statistics.dto.StrResponse;
import com.str.backend.statistics.dto.PlatformActivitiesPageDto;
import com.str.backend.statistics.dto.PlatformBreakdownDto;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService service;
    private final PlatformActivityQuery platformActivityQuery;
    private final StatisticsExportService exportService;

    public StatisticsController(StatisticsService service,
                                PlatformActivityQuery platformActivityQuery,
                                StatisticsExportService exportService) {
        this.service = service;
        this.platformActivityQuery = platformActivityQuery;
        this.exportService = exportService;
    }

    /** Wireframe §11: STR dashboard — RB counts per county, optionally filtered by issue-date range. */
    @GetMapping("/str")
    public StrResponse str(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.str(from, to);
    }

    /** Wireframe §11: STR PDF export — summary with KPI totals and county breakdown. */
    @GetMapping("/str/export/pdf")
    public ResponseEntity<byte[]> strPdf() {
        byte[] pdf = exportService.generateStrPdf();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"str-statistika.pdf\"")
                .body(pdf);
    }

    /** Wireframe §11: STR detail CSV export — one row per RN. */
    @GetMapping("/str/detail/csv")
    public ResponseEntity<byte[]> strCsv() {
        byte[] csv = exportService.generateStrCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"str-detaljna-statistika.csv\"")
                .body(csv);
    }

    /** Wireframe §11: STR detail Excel export — one row per RN. */
    @GetMapping("/str/detail/xlsx")
    public ResponseEntity<byte[]> strXlsx() {
        byte[] xlsx = exportService.generateStrXlsx();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"str-detaljna-statistika.xlsx\"")
                .body(xlsx);
    }

    /** Wireframe §12: SDIP dashboard — platform activity report, grouped by (RN × period). */
    @GetMapping("/platform-activities")
    public PlatformActivitiesPageDto platformActivities(
            @RequestParam(required = false) Long platformId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate od,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String rn,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int clampedSize = Math.min(Math.max(1, size), 100);
        return platformActivityQuery.query(platformId, od, toDate, county, status, q, rn, page, clampedSize);
    }

    // TODO(auth/BX0): role-gate platform-activities export (voditelj/admin) kad stignu NIAS role —
    // izvoz sadrži imena vlasnika i adrese, a endpointi su trenutno permitAll.

    /** STR-3.2: platform activity report → Excel (obavezni izvoz). Same filters as the list. */
    @GetMapping("/platform-activities/xlsx")
    public ResponseEntity<byte[]> platformActivitiesXlsx(
            @RequestParam(required = false) Long platformId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate od,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String rn) {
        byte[] xlsx = exportService.generatePlatformActivitiesXlsx(platformId, od, toDate, county, status, q, rn);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"aktivnosti-platformi.xlsx\"")
                .body(xlsx);
    }

    /** STR-3.2: platform activity report → CSV. Same filters as the list. */
    @GetMapping("/platform-activities/csv")
    public ResponseEntity<byte[]> platformActivitiesCsv(
            @RequestParam(required = false) Long platformId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate od,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String rn) {
        byte[] csv = exportService.generatePlatformActivitiesCsv(platformId, od, toDate, county, status, q, rn);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"aktivnosti-platformi.csv\"")
                .body(csv);
    }

    /** Accordion: per-platform per-country breakdown for a single (RN × period) row. */
    @GetMapping("/platform-activities/breakdown")
    public PlatformBreakdownDto platformActivityBreakdown(
            @RequestParam String rn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodTo) {
        return platformActivityQuery.breakdown(rn, periodFrom, periodTo);
    }
}
