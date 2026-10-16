package com.str.backend.admin;

import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.categorization.CategorizationDecisionAdminDto;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.categorization.CategorizationFileDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Interni ekran nadležnog tijela za skenirana rješenja o kategorizaciji (MINTS dashboard,
 * dio „ostaje"). Isti položaj i sigurnosni obrazac kao {@link AdminPendingRegistrationController}.
 */
@RestController
@RequestMapping("/api/admin/categorization-decisions")
public class AdminCategorizationDecisionController {

    private final AdminCategorizationDecisionService service;
    private final InternalAccessService internalAccess;

    public AdminCategorizationDecisionController(AdminCategorizationDecisionService service,
                                                 InternalAccessService internalAccess) {
        this.service = service;
        this.internalAccess = internalAccess;
    }

    record ReviewDecisionRequest(@Nullable @Size(max = 1000) String reason) {
        ReviewDecisionRequest {
            if (reason != null && reason.isBlank()) reason = null;
        }
    }

    @GetMapping
    public Page<CategorizationDecisionAdminDto> list(
            @RequestParam(required = false) CategorizationDecisionStatus status,
            @PageableDefault(size = 20, sort = "uploadedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(status, pageable);
    }

    @GetMapping("/{id}")
    public CategorizationDecisionAdminDto detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        CategorizationFileDto f = service.file(id);
        // ContentDisposition RFC 5987-enkodira naziv (UTF-8): hrvatski znakovi u imenu skena
        // ostaju čitljivi, a CR/LF/navodnici se sigurno enkodiraju (nema header injectiona).
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(f.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }

    /** Akter je službenik iz sesije ({@link InternalAccessService#actorFor}), nikad iz tijela zahtjeva. */
    @PostMapping("/{id}/verify")
    public ResponseEntity<Void> verify(@PathVariable UUID id, Authentication authentication) {
        service.verify(id, internalAccess.actorFor(authentication).orElse(null));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id,
                                       @RequestBody(required = false) @Valid ReviewDecisionRequest body,
                                       Authentication authentication) {
        service.reject(id, internalAccess.actorFor(authentication).orElse(null),
                body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }
}
