package com.str.backend.auth;

import com.str.backend.auth.dto.MeResponse;
import com.str.backend.auth.nias.NiasIdentity;
import com.str.backend.auth.nias.NiasOibExtractor;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.auth.role.InternalUserResolver;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Gradi unificirani {@link MeResponse} iz trenutne {@link Authentication}, neovisno o toku prijave:
 * <ul>
 *   <li>{@link LessorPrincipal} (username/password) → identitet iz {@code LessorEntity}.</li>
 *   <li>NIAS SAML → identitet iz assertiona ({@link NiasOibExtractor#extractIdentity}); na local/mock
 *       profilu fallback na konfigurirani mock OIB preko {@link NiasOibResolver}.</li>
 * </ul>
 * Za NIAS korisnika NE ovisi o postojanju {@code LessorEntity} u bazi.
 */
@Service
public class SessionIdentityResolver {

    private static final String AUTH_TYPE_LOCAL = "LOCAL";
    private static final String AUTH_TYPE_NIAS = "NIAS";

    private final LessorRepository lessorRepository;
    private final NiasOibResolver niasOibResolver;
    private final InternalUserResolver roleResolver;

    public SessionIdentityResolver(LessorRepository lessorRepository,
                                   NiasOibResolver niasOibResolver,
                                   InternalUserResolver roleResolver) {
        this.lessorRepository = lessorRepository;
        this.niasOibResolver = niasOibResolver;
        this.roleResolver = roleResolver;
    }

    @Transactional(readOnly = true)
    public MeResponse resolve(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof LessorPrincipal principal) {
            return buildLocal(principal.getLessorId());
        }

        Optional<NiasIdentity> identity = NiasOibExtractor.extractIdentity(authentication);
        if (identity.isPresent()) {
            NiasIdentity id = identity.get();
            return nias(id.oib(), id.firstName(), id.lastName());
        }

        // local/mock: nema pravog SAML principala, ali nias.mock.fixed-oib daje OIB
        Optional<String> mockOib = niasOibResolver.resolve(authentication);
        if (mockOib.isPresent()) {
            return nias(mockOib.get(), null, null);
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private MeResponse buildLocal(UUID lessorId) {
        LessorEntity e = lessorRepository.findById(lessorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Korisnik nije pronađen."));
        return new MeResponse(AUTH_TYPE_LOCAL, e.getLessorOib(), e.getLessorId(),
                e.getUsername(), e.getFirstName(), e.getLastName(), LessorPrincipal.ROLE, e.getEmail());
    }

    // NIAS assertion ne nosi rolu ni email. Rolu izvodimo iz OIB-a (str.application_user.internal);
    // email ostaje null. Role: ROLE_INTERNAL (interni službenik) ili ROLE_USER (obični građanin).
    private MeResponse nias(String oib, String firstName, String lastName) {
        return new MeResponse(AUTH_TYPE_NIAS, oib, null, null, firstName, lastName,
                roleResolver.resolveRole(oib), null);
    }
}
