package com.str.backend.activity;

import com.str.backend.activity.dto.ActivityResponse;
import com.str.backend.activity.dto.SdepIngestRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/activity")
@Validated
public class AccommodationActivityController {

    private final AccommodationActivityService service;
    private final ActivityMapper mapper;

    public AccommodationActivityController(AccommodationActivityService service, ActivityMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** STR-3.1: SDEP monthly ingestion (also triggerable by Administrator sustava). */
    @PostMapping("/ingest")
    public ResponseEntity<Integer> ingest(@Valid @RequestBody SdepIngestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.ingest(req));
    }

    /** STR-3.2: Competent authority / process manager search. */
    @GetMapping
    public List<ActivityResponse> search(
            @RequestParam(required = false) Long platformId,
            @RequestParam(required = false) String rn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate od,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return mapper.toResponseList(service.search(platformId, rn, od, toDate));
    }

    /** STR-3.3: manual auto-purge trigger (18-month retention); otherwise runs via scheduler. */
    @DeleteMapping("/purge")
    public int purge() {
        return service.purgeExpired();
    }
}
