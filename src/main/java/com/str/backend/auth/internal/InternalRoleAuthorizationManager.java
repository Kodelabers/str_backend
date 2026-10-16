package com.str.backend.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Pušta zahtjev samo internom korisniku sa zadanom rolom. Neprijavljen dobiva 401, prijavljen
 * bez role 403 (razlikuje ih {@code ExceptionTranslationFilter}).
 *
 * <p>Kad se role ne mogu pročitati (baza {@code str} nedostupna), pristup se odbija.
 */
public class InternalRoleAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Logger log = LoggerFactory.getLogger(InternalRoleAuthorizationManager.class);

    private final InternalAccessService internalAccess;
    private final String role;

    public InternalRoleAuthorizationManager(InternalAccessService internalAccess, String role) {
        this.internalAccess = internalAccess;
        this.role = role;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        try {
            return new AuthorizationDecision(internalAccess.hasRole(authentication.get(), role));
        } catch (DataAccessException e) {
            log.error("internal_role_check_failed role={} uri={}", role, context.getRequest().getRequestURI(), e);
            return new AuthorizationDecision(false);
        }
    }
}
