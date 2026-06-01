package com.str.backend.statistics;

import com.str.backend.statistics.dto.BpsoResponse;
import com.str.backend.statistics.dto.PlatformActivitiesPageDto;
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

    /** Wireframe §11: BPSO dashboard — RB counts per county, optionally filtered by year/month snapshot. */
    @GetMapping("/bpso")
    public BpsoResponse bpso(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return service.bpso(year, month);
    }

    /** Wireframe §11: BPSO PDF export — summary with KPI totals and county breakdown. */
    @GetMapping("/bpso/export/pdf")
    public ResponseEntity<byte[]> bpsoPdf() {
        byte[] pdf = exportService.generateBpsoPdf();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bpso-statistika.pdf\"")
                .body(pdf);
    }

    /** Wireframe §11: BPSO detail CSV export — one row per RN. */
    @GetMapping("/bpso/detail/csv")
    public ResponseEntity<byte[]> bpsoCsv() {
        byte[] csv = exportService.generateBpsoCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bpso-detaljna-statistika.csv\"")
                .body(csv);
    }

    /** Wireframe §11: BPSO detail Excel export — one row per RN. */
    @GetMapping("/bpso/detail/xlsx")
    public ResponseEntity<byte[]> bpsoXlsx() {
        byte[] xlsx = exportService.generateBpsoXlsx();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bpso-detaljna-statistika.xlsx\"")
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
}
