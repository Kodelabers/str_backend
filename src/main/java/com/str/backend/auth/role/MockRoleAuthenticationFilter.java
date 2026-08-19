package com.str.backend.auth.role;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Local/mock samo: kad je {@code nias.mock.fixed-oib} postavljen (dakle nema prave NIAS SAML
 * sesije), ubaci mock {@link Authentication} s ulogom razriješenom iz {@link InternalUserResolver}
 * za taj OIB. Time URL role-gating radi jednako kao na dev/cdu, a interni/USER se lokalno testira
 * mijenjanjem {@code nias.mock.fixed-oib}.
 *
 * <p>Namjerno je to {@link PreAuthenticatedAuthenticationToken} (a NE {@code Saml2Authentication}):
 * {@code NiasOibExtractor} ga ne prepoznaje, pa se OIB u kontrolerima i dalje razrješuje preko
 * {@code NiasOibResolver} (property fallback) kao i dosad — mijenjaju se samo ovlasti za gating.
 *
 * <p>Na dev/cdu je {@code nias.mock.fixed-oib} prazan → filter je no-op.
 */
public class MockRoleAuthenticationFilter extends OncePerRequestFilter {

    private final InternalUserResolver roleResolver;
    private final String mockOib;

    public MockRoleAuthenticationFilter(InternalUserResolver roleResolver, String mockOib) {
        this.roleResolver = roleResolver;
        this.mockOib = (mockOib == null || mockOib.isBlank()) ? null : mockOib.trim();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (mockOib != null && needsMockAuth()) {
            List<GrantedAuthority> authorities = roleResolver.resolveAuthorities(mockOib);
            PreAuthenticatedAuthenticationToken token =
                    new PreAuthenticatedAuthenticationToken(mockOib, "N/A", authorities);
            token.setAuthenticated(true);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(token);
            SecurityContextHolder.setContext(context);
        }
        filterChain.doFilter(request, response);
    }

    private static boolean needsMockAuth() {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        return existing == null
                || !existing.isAuthenticated()
                || existing instanceof AnonymousAuthenticationToken;
    }
}
