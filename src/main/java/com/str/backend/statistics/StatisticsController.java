package com.str.backend.statistics;

import com.str.backend.statistics.dto.BpsoResponse;
import com.str.backend.statistics.dto.PlatformActivitiesPageDto;
import org.springframework.format.annotation.DateTimeFormat;
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

    public StatisticsController(StatisticsService service, PlatformActivityQuery platformActivityQuery) {
        this.service = service;
        this.platformActivityQuery = platformActivityQuery;
    }

    /** Wireframe §11: BPSO dashboard — RB counts per county. */
    @GetMapping("/bpso")
    public BpsoResponse bpso() {
        return service.bpso();
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int clampedSize = Math.min(Math.max(1, size), 100);
        return platformActivityQuery.query(platformId, od, toDate, county, status, q, page, clampedSize);
    }
}
