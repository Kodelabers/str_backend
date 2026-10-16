package com.str.backend.auth.internal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Role prijavljenog NIAS korisnika, za izbornik i čuvare ruta na frontendu. Autorizacija na
 * backendu ne ovisi o ovom odgovoru — svaki interni endpoint provjerava rolu sam
 * ({@link InternalRoleAuthorizationManager}).
 *
 * <p>Pod {@code /api/nias/**}, a ne u {@code /api/auth/me}: lokalni mock tok nema prijave
 * (samo {@code nias.mock.fixed-oib}), pa bi {@code /me} vratio 401.
 */
@RestController
public class InternalAccessController {

    public record InternalAccessResponse(List<String> roles) {}

    private final InternalAccessService internalAccess;

    public InternalAccessController(InternalAccessService internalAccess) {
        this.internalAccess = internalAccess;
    }

    @GetMapping("/api/nias/internal-access")
    public InternalAccessResponse internalAccess(Authentication authentication) {
        return new InternalAccessResponse(internalAccess.rolesFor(authentication).stream().sorted().toList());
    }
}
