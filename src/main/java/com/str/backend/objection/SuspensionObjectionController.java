package com.str.backend.objection;

import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.exception.ForbiddenException;
import com.str.backend.rn.RnScopeGuard;
import com.str.backend.rn.RucniAkt;
import com.str.backend.rn.RucniAktService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final RucniAktService rucniAkt;

    public SuspensionObjectionController(SuspensionObjectionAdminService service,
                                         InternalAccessService internalAccess,
                                         RnScopeGuard scopeGuard,
                                         RucniAktService rucniAkt) {
        this.rucniAkt = rucniAkt;
        this.service = service;
        this.internalAccess = internalAccess;
        this.scopeGuard = scopeGuard;
    }

    /** {@code klasa} i {@code urbroj}: ručni akt županijskog službenika uz uvažavanje (T16). */
    record ResolveObjectionRequest(@Nullable @Size(max = 1000) String note,
                                   @Nullable String klasa,
                                   @Nullable String urbroj) {
    }

    /** KLASA i URBROJ kojima županijski službenik urudžbira podnesak (T16). */
    record RegisterObjectionRequest(@Nullable String klasa, @Nullable String urbroj) {
    }

    @GetMapping
    public List<SuspensionObjectionDto> list(@PathVariable String rn, Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        return service.list(rn);
    }

    /** Dokument prigovora ({@link SuspensionObjectionFileDto#toResponse}: PDF inline, Word kao privitak). */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String rn, @PathVariable UUID id,
                                       Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        return service.file(rn, id).toResponse();
    }

    /**
     * Urudžbiranje očitovanja ili prigovora u postupku koji vodi županija (T16): službenik upisuje KLASU i
     * URBROJ ({@link RucniAktService#zaRadnju}: 400 kad ne valjaju), a podnesak postaje ulazno pismeno u
     * „Dokumentima" RB-a. Službenik koji nije županijski (ministarstvo, neodređen opseg) ne može — 403
     * {@code OBJECTION_NOT_COUNTY_OFFICIAL}; ostala pravila (409) u
     * {@link SuspensionObjectionAdminService#register}.
     */
    @PostMapping("/{id}/register")
    public SuspensionObjectionDto register(@PathVariable String rn, @PathVariable UUID id,
                                           @RequestBody(required = false) RegisterObjectionRequest body,
                                           Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        RucniAkt rucni = rucniAkt.zaRadnju(authentication, body != null ? body.klasa() : null,
                body != null ? body.urbroj() : null);
        if (rucni == null) {
            throw new ForbiddenException("error.objection.notCountyOfficial", "OBJECTION_NOT_COUNTY_OFFICIAL");
        }
        return service.register(rn, id, actor(authentication), rucni);
    }

    /** Akter je službenik iz sesije ({@link InternalAccessService#actorFor}), nikad iz tijela zahtjeva. */
    @PostMapping("/{id}/accept")
    public ResponseEntity<Void> accept(@PathVariable String rn, @PathVariable UUID id,
                                       @RequestBody(required = false) @Valid ResolveObjectionRequest body,
                                       Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        RucniAkt rucni = rucniAkt.zaRadnju(authentication, body != null ? body.klasa() : null,
                body != null ? body.urbroj() : null);
        service.accept(rn, id, actor(authentication), body != null ? body.note() : null, rucni);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable String rn, @PathVariable UUID id,
                                       @RequestBody(required = false) @Valid ResolveObjectionRequest body,
                                       Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
        service.reject(rn, id, actor(authentication), body != null ? body.note() : null,
                rucniAkt.zupanijskiSluzbenik(authentication));
        return ResponseEntity.noContent().build();
    }

    private String actor(Authentication authentication) {
        return internalAccess.actorFor(authentication).orElse(null);
    }
}
