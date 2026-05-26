package com.str.backend.admin;

import com.str.backend.admin.dto.PendingRegistrationDetailDto;
import com.str.backend.admin.dto.PendingRegistrationStatsDto;
import com.str.backend.admin.dto.PendingRegistrationSummaryDto;
import com.str.backend.domain.LessorApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/pending-registrations")
public class AdminPendingRegistrationController {

    private final AdminPendingRegistrationService service;

    public AdminPendingRegistrationController(AdminPendingRegistrationService service) {
        this.service = service;
    }

    record ReviewDecisionRequest(@Nullable @Size(max = 64) String actorId) {
        ReviewDecisionRequest {
            if (actorId != null && actorId.isBlank()) actorId = null;
        }
    }

    @GetMapping
    public Page<PendingRegistrationSummaryDto> list(
            @RequestParam(required = false) LessorApplicationStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String documentType,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.search(status, q, country, documentType, pageable);
    }

    @GetMapping("/stats")
    public PendingRegistrationStatsDto stats() {
        return service.getStats();
    }

    @GetMapping("/{lessorId}")
    public PendingRegistrationDetailDto detail(@PathVariable UUID lessorId) {
        return service.getDetail(lessorId);
    }

    @GetMapping("/{lessorId}/document/front")
    public ResponseEntity<byte[]> frontImage(@PathVariable UUID lessorId) {
        byte[] image = service.getFrontImage(lessorId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"front\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(image);
    }

    @GetMapping("/{lessorId}/document/back")
    public ResponseEntity<byte[]> backImage(@PathVariable UUID lessorId) {
        byte[] image = service.getBackImage(lessorId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"back\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(image);
    }

    @PostMapping("/{lessorId}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID lessorId,
                                        // TODO(auth): once ROLE_ADMIN is gated, derive actorId from SecurityContext instead
                                        @RequestBody(required = false) @Valid ReviewDecisionRequest body) {
        service.approve(lessorId, body != null ? body.actorId() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{lessorId}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID lessorId,
                                       // TODO(auth): once ROLE_ADMIN is gated, derive actorId from SecurityContext instead
                                       @RequestBody(required = false) @Valid ReviewDecisionRequest body) {
        service.reject(lessorId, body != null ? body.actorId() : null);
        return ResponseEntity.noContent().build();
    }
}
