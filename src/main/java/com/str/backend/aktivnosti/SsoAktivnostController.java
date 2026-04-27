package com.str.backend.aktivnosti;

import com.str.backend.aktivnosti.dto.AktivnostResponse;
import com.str.backend.aktivnosti.dto.SdepIngestRequest;
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
@RequestMapping("/api/aktivnosti")
@Validated
public class SsoAktivnostController {

    private final SsoAktivnostService service;
    private final AktivnostMapper mapper;

    public SsoAktivnostController(SsoAktivnostService service, AktivnostMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** STR-3.1: SDEP monthly ingestion (also triggerable by Administrator sustava). */
    @PostMapping("/ingest")
    public ResponseEntity<Integer> ingest(@Valid @RequestBody SdepIngestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.ingest(req));
    }

    /** STR-3.2: Nadležno tijelo / Voditelj postupka pretraživanje. */
    @GetMapping
    public List<AktivnostResponse> pretrazi(
            @RequestParam(required = false) Long idPlatforme,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate od,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate doDate) {
        return mapper.toResponseList(service.pretrazi(idPlatforme, rb, od, doDate));
    }

    /** STR-3.3: ručno pokretanje auto-purga (18 mj retencija); inače pokreće scheduler. */
    @DeleteMapping("/purge")
    public int purge() {
        return service.purgeExpired();
    }
}
