package com.str.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CSRF obrana za mutirajuće radnje pod sesijom: zahtjev mora nositi
 * {@code X-Requested-With: XMLHttpRequest}. HTML forma s tuđe stranice zaglavlje ne može
 * postaviti, a cross-origin {@code fetch} s njim mora proći CORS preflight, koji pušta samo
 * dopuštene origine.
 *
 * <p>Potrebno jer je CSRF token isključen, a na CDU/cdupreprod/mock sesijski cookie je
 * {@code SameSite=None} (NIAS na ACS šalje cross-site POST), pa ga preglednik šalje i uz
 * zahtjev s tuđe stranice. Do sada je takav zahtjev zaustavljalo samo CORS odbijanje stranog
 * {@code Origin} zaglavlja — što puca uz wildcard u {@code app.cors.allowed-origins} ili kad
 * {@code Origin} izostane.
 */
public class RequiredAjaxHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Requested-With";
    public static final String VALUE = "XMLHttpRequest";

    private final RequestMatcher protectedRequests;

    public RequiredAjaxHeaderFilter(RequestMatcher protectedRequests) {
        this.protectedRequests = protectedRequests;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (protectedRequests.matches(request) && !VALUE.equals(request.getHeader(HEADER))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing " + HEADER + " header");
            return;
        }
        chain.doFilter(request, response);
    }
}
