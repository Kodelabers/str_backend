package com.str.backend.admin;

import com.str.backend.admin.dto.PendingRegistrationDetailDto;
import com.str.backend.auth.internal.InternalAccessService;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/pending-registrations")
public class AdminPendingRegistrationController {

    private final AdminPendingRegistrationService service;
    private final InternalAccessService internalAccess;

    public AdminPendingRegistrationController(AdminPendingRegistrationService service,
                                              InternalAccessService internalAccess) {
        this.service = service;
        this.internalAccess = internalAccess;
    }

    @GetMapping
    public Page<PendingRegistrationSummaryDto> list(
            @RequestParam(required = false) LessorApplicationStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String taxNumber,
            @RequestParam(required = false) String documentNumber,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.search(status, q, country, documentType, name, email, taxNumber, documentNumber, pageable);
    }

    @GetMapping("/export/xlsx")
    public ResponseEntity<byte[]> exportXlsx(
            @RequestParam(required = false) LessorApplicationStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String taxNumber,
            @RequestParam(required = false) String documentNumber) {
        byte[] bytes = service.exportXlsx(status, q, country, documentType,
                name, email, taxNumber, documentNumber);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"zahtjevi-za-registraciju.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
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

    /** Akter je službenik iz sesije ({@link InternalAccessService#actorFor}), nikad iz tijela zahtjeva. */
    @PostMapping("/{lessorId}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID lessorId, Authentication authentication) {
        service.approve(lessorId, internalAccess.actorFor(authentication).orElse(null));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{lessorId}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID lessorId, Authentication authentication) {
        service.reject(lessorId, internalAccess.actorFor(authentication).orElse(null));
        return ResponseEntity.noContent().build();
    }
}
