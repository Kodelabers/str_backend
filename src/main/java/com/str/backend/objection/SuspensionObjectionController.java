package com.str.backend.objection;

import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.rn.RnScopeGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Prigovori uz RB na detalju RB-a. Pod {@code /api/rn/**}, pa vrijedi isto što i za ostale
 * radnje službenika nad RB-om: rola {@code RN_MANAGEMENT} i CSRF zaglavlje ({@code SecurityConfig}),
 * a RB izvan opsega službenika (druga županija) je 404 ({@link RnScopeGuard}).
 */
@RestController
@RequestMapping("/api/rn/{rn}/objections")
public class SuspensionObjectionController {

    private final SuspensionObjectionAdminService service;
    private final InternalAccessService internalAccess;
    private final RnScopeGuard scopeGuard;

    public SuspensionObjectionController(SuspensionObjectionAdminService service,
                                         InternalAccessService internalAccess,
                                         RnScopeGuard scopeGuard) {
        this.service = service;
        this.internalAccess = internalAccess;
        this.scopeGuard = scopeGuard;
    }

    record ResolveObjectionRequest(@Nullable @Size(max = 1000) String note) {
    }

    @GetMapping
    public List<SuspensionObjectionDto> list(@PathVariable String rn, Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        return service.list(rn);
    }

    /**
     * Dokument prigovora. PDF se otvara u pregledniku, Word se preuzima — preglednik ga ne
     * prikazuje. {@link ContentDisposition} RFC 5987-enkodira naziv (hrvatski znakovi, bez
     * header injectiona).
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String rn, @PathVariable UUID id,
                                       Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        SuspensionObjectionFileDto f = service.file(rn, id);
        ContentDisposition.Builder builder = SuspensionObjectionService.CONTENT_TYPE_PDF.equals(f.contentType())
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        ContentDisposition disposition = builder.filename(f.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }

    /** Akter je službenik iz sesije ({@link InternalAccessService#actorFor}), nikad iz tijela zahtjeva. */
    @PostMapping("/{id}/accept")
    public ResponseEntity<Void> accept(@PathVariable String rn, @PathVariable UUID id,
                                       @RequestBody(required = false) @Valid ResolveObjectionRequest body,
                                       Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        service.accept(rn, id, actor(authentication), body != null ? body.note() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable String rn, @PathVariable UUID id,
                                       @RequestBody(required = false) @Valid ResolveObjectionRequest body,
                                       Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        service.reject(rn, id, actor(authentication), body != null ? body.note() : null);
        return ResponseEntity.noContent().build();
    }

    private String actor(Authentication authentication) {
        return internalAccess.actorFor(authentication).orElse(null);
    }
}
