package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.auth.nias.EffectiveOibResolver;
import com.str.backend.auth.nias.NiasOibExtractor;
import com.str.backend.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Pristup pojedinom RB-u za dokumente i priloge: vlasnik (non-EU po {@code lessorId}, NIAS po
 * efektivnom OIB-u — zastupnik vidi RB-ove tvrtke u čije ime djeluje) ili službenik s rolom
 * {@link InternalAccessService#RN_MANAGEMENT} u svojem opsegu ({@link RnScopeGuard}). Bez prijave
 * 401, inače 404 — da se ne otkriva postoji li tuđi RB.
 *
 * <p>Isto pravilo kao {@code RnController#requireAccess} za {@code /api/rn/{rn}/documents/**};
 * prilozi ga dijele ({@code GET /api/rn/{rn}/attachments/{id}}). TODO(T17/B-2): neka i
 * {@code RnController} zove ovaj guard, da pravilo živi na jednom mjestu.
 */
@Component
public class RnAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(RnAccessGuard.class);

    public enum Pristup { VLASNIK, SLUZBENIK }

    private final RnRepository rnRepository;
    private final EffectiveOibResolver effectiveOibResolver;
    private final InternalAccessService internalAccess;
    private final RnScopeGuard scopeGuard;

    public RnAccessGuard(RnRepository rnRepository, EffectiveOibResolver effectiveOibResolver,
                         InternalAccessService internalAccess, RnScopeGuard scopeGuard) {
        this.rnRepository = rnRepository;
        this.effectiveOibResolver = effectiveOibResolver;
        this.internalAccess = internalAccess;
        this.scopeGuard = scopeGuard;
    }

    public Pristup requireAccess(String rn, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof LessorPrincipal principal) {
            if (rnRepository.isOwnedByLessor(rn, principal.getLessorId())) {
                return Pristup.VLASNIK;
            }
            throw new ResourceNotFoundException("rn not found: " + rn);
        }
        Optional<String> oib = NiasOibExtractor.extractOib(authentication).isPresent()
                ? effectiveOibResolver.resolve(authentication) : Optional.empty();
        if (oib.isPresent() && rnRepository.isOwnedByOib(rn, oib.get())) {
            return Pristup.VLASNIK;
        }
        // Lokalni mock NIAS-a nema autentikacije — rolu tada daje nias.mock.fixed-oib.
        if (isOfficial(authentication)) {
            scopeGuard.requireInScope(rn, authentication);
            return Pristup.SLUZBENIK;
        }
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        throw new ResourceNotFoundException("rn not found: " + rn);
    }

    /** Nedostupna shema {@code str} znači „nije službenik" — vlasnik je već prošao gore. */
    private boolean isOfficial(Authentication authentication) {
        try {
            return internalAccess.hasRole(authentication, InternalAccessService.RN_MANAGEMENT);
        } catch (DataAccessException e) {
            log.error("internal_role_check_failed context=rn_access", e);
            return false;
        }
    }
}
