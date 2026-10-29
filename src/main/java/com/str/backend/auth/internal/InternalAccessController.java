package com.str.backend.auth.internal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Uloga prijavljenog NIAS korisnika ({@code RN_MANAGEMENT} ili {@code USER}) i, za službenika,
 * opseg podataka (županija ili cijela RH), za izbornik, oznaku i zaključane filtre na frontendu.
 * Autorizacija na backendu ne ovisi o ovom odgovoru — svaki interni endpoint provjerava rolu
 * ({@link InternalRoleAuthorizationManager}) i opseg ({@link CountyScopeService}) sam.
 *
 * <p>Pod {@code /api/nias/**}, a ne u {@code /api/auth/me}: lokalni mock tok nema prijave
 * (samo {@code nias.mock.fixed-oib}), pa bi {@code /me} vratio 401.
 */
@RestController
public class InternalAccessController {

    /**
     * @param subjectName interna organizacija službenika (eTurizam); {@code null} kad je nema
     * @param countyId    {@code null} = cijela RH (i za svakoga tko nije službenik)
     */
    public record InternalAccessResponse(String role, String subjectName, Long countyId, String countyName) {}

    private final InternalAccessService internalAccess;
    private final CountyScopeService countyScope;

    public InternalAccessController(InternalAccessService internalAccess, CountyScopeService countyScope) {
        this.internalAccess = internalAccess;
        this.countyScope = countyScope;
    }

    @GetMapping("/api/nias/internal-access")
    public InternalAccessResponse internalAccess(Authentication authentication) {
        String role = internalAccess.effectiveRole(authentication);
        if (!InternalAccessService.RN_MANAGEMENT.equals(role)) {
            return new InternalAccessResponse(role, null, null, null);
        }
        CountyScope scope = countyScope.scopeFor(authentication);
        return new InternalAccessResponse(role, scope.subjectName(), scope.countyId(), scope.countyName());
    }
}
