package com.str.backend.activity;

import com.str.backend.activity.dto.ActivityResponse;
import com.str.backend.activity.dto.SdepIngestRequest;
import com.str.backend.auth.internal.CountyScopeService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/activity")
@Validated
public class AccommodationActivityController {

    private final AccommodationActivityService service;
    private final ActivityMapper mapper;
    private final CountyScopeService countyScope;

    public AccommodationActivityController(AccommodationActivityService service, ActivityMapper mapper,
                                           CountyScopeService countyScope) {
        this.service = service;
        this.mapper = mapper;
        this.countyScope = countyScope;
    }

    /**
     * STR-3.1: SDEP monthly ingestion (also triggerable by Administrator sustava). Sistemska radnja
     * za cijelu RH — županijski službenik je ne smije pokrenuti ({@link #requireNationalScope}).
     */
    @PostMapping("/ingest")
    public ResponseEntity<Integer> ingest(@Valid @RequestBody SdepIngestRequest req, Authentication authentication) {
        requireNationalScope(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.ingest(req));
    }

    /** STR-3.2: Competent authority / process manager search — u opsegu službenika (županija). */
    @GetMapping
    public List<ActivityResponse> search(
            @RequestParam(required = false) Long platformId,
            @RequestParam(required = false) String rn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate od,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Authentication authentication) {
        return mapper.toResponseList(service.search(platformId, rn, od, toDate,
                countyScope.accommodationCounties(authentication)));
    }

    /** STR-3.3: manual auto-purge trigger (18-month retention); otherwise runs via scheduler. Kao ingest. */
    @DeleteMapping("/purge")
    public int purge(Authentication authentication) {
        requireNationalScope(authentication);
        return service.purgeExpired();
    }

    /**
     * Unos i brisanje aktivnosti mijenjaju podatke svih županija, pa ih smije samo onaj tko nije
     * ograničen na jednu (MINTS i ostali bez županije) — županijski službenik dobiva 403.
     */
    private void requireNationalScope(Authentication authentication) {
        if (countyScope.scopeFor(authentication).restricted()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
