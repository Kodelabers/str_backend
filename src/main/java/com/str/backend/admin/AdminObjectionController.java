package com.str.backend.admin;

import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.objection.AdminObjectionRowDto;
import com.str.backend.objection.AdminObjectionStatsDto;
import com.str.backend.objection.ObjectionKind;
import com.str.backend.objection.ObjectionResolution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Interni popis ulaznih pismena (očitovanja i prigovori) preko svih RB-ova — MINTS 17.11., točka 1. Isti
 * položaj i opseg kao {@link AdminCategorizationDecisionController}; rješava se na detalju RB-a
 * ({@code /api/rn/{rn}/objections}).
 *
 * <p>{@code status} je riješenost ({@link ObjectionResolution}, zadano neriješeni), {@code kind} vrsta pismena,
 * a {@code olderThanDays} jedan od pragova 5/15/30/45/60. Sortira se samo po {@code submittedAt}.
 */
@RestController
@RequestMapping("/api/admin/objections")
public class AdminObjectionController {

    private final AdminObjectionService service;
    private final CountyScopeService countyScope;

    public AdminObjectionController(AdminObjectionService service, CountyScopeService countyScope) {
        this.service = service;
        this.countyScope = countyScope;
    }

    @GetMapping
    public Page<AdminObjectionRowDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String oib,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) ObjectionKind kind,
            @RequestParam(required = false) ObjectionResolution status,
            @RequestParam(required = false) Integer olderThanDays,
            @PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.ASC) Pageable pageable,
            Authentication authentication) {
        return service.search(new AdminObjectionService.Filter(q, name, oib, address, rb, kind, status, olderThanDays),
                countyScope.accommodationCounties(authentication), pageable);
    }

    @GetMapping("/stats")
    public AdminObjectionStatsDto stats(Authentication authentication) {
        return service.stats(countyScope.accommodationCounties(authentication));
    }

    @GetMapping("/export/xlsx")
    public ResponseEntity<byte[]> exportXlsx(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String oib,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) ObjectionKind kind,
            @RequestParam(required = false) ObjectionResolution status,
            @RequestParam(required = false) Integer olderThanDays,
            Sort sort,
            Authentication authentication) {
        byte[] xlsx = service.exportXlsx(new AdminObjectionService.Filter(q, name, oib, address, rb, kind, status,
                olderThanDays), countyScope.accommodationCounties(authentication), sort);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ocitovanja-i-prigovori.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }
}
