package com.str.backend.auth.nias;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.HttpSessionSaml2AuthenticationRequestRepository;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

/**
 * Springov {@link HttpSessionSaml2AuthenticationRequestRepository} uz {@link NiasReturnTo}: put iz
 * {@code ?returnTo=} pamti se u sesiji zajedno s AuthnRequestom, a kad ga Spring na ACS-u potroši,
 * premješta se u request atribut za success handler.
 *
 * <p>Radi samo kad sesijski cookie preživi NIAS-ov cross-site POST na ACS ({@code SameSite=None;
 * Secure}) — isti uvjet koji {@code session} mode ionako traži za sam AuthnRequest.
 */
public class SessionReturnToAuthenticationRequestRepository
        implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {

    private final Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> delegate =
            new HttpSessionSaml2AuthenticationRequestRepository();

    @Override
    public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
        return delegate.loadAuthenticationRequest(request);
    }

    @Override
    public void saveAuthenticationRequest(AbstractSaml2AuthenticationRequest authenticationRequest,
                                          HttpServletRequest request, HttpServletResponse response) {
        delegate.saveAuthenticationRequest(authenticationRequest, request, response);
        if (authenticationRequest == null) {
            return;
        }
        // Nova prijava uvijek prepisuje put prethodne, pa napuštena prijava ne ostavlja svoj put.
        HttpSession session = request.getSession();
        NiasReturnTo.fromRequest(request).ifPresentOrElse(
                returnTo -> session.setAttribute(NiasReturnTo.SESSION_KEY, returnTo),
                () -> session.removeAttribute(NiasReturnTo.SESSION_KEY));
    }

    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(HttpServletRequest request,
                                                                          HttpServletResponse response) {
        AbstractSaml2AuthenticationRequest removed = delegate.removeAuthenticationRequest(request, response);
        HttpSession session = request.getSession(false);
        if (removed != null && session != null) {
            Object returnTo = session.getAttribute(NiasReturnTo.SESSION_KEY);
            session.removeAttribute(NiasReturnTo.SESSION_KEY);
            if (returnTo != null) {
                request.setAttribute(NiasReturnTo.REQUEST_ATTR, returnTo);
            }
        }
        return removed;
    }
}
