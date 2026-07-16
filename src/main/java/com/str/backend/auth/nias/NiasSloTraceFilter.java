package com.str.backend.auth.nias;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TODO(SLO-debug): PRIVREMENI trace filter — bilježi SVAKI dolazni zahtjev na {@code /logout/saml2/**}
 * i pripadni odgovor (status + Location). Postavljen je na HIGHEST_PRECEDENCE pa hvata zahtjev i
 * PRIJE Spring Security lanca — tako vidimo i zahtjeve koje security odbije.
 *
 * Svrha: odgovoriti na dva pitanja iz NIAS SLO dijagnostike —
 *  1) zove li NIAS naš SOAP back-channel (spec korak 5), i
 *  2) vraća li NIAS LogoutResponse na naš HTTP SLO endpoint (spec korak 11).
 *
 * Ukloniti nakon dijagnostike.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
public class NiasSloTraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NiasSloTraceFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/logout/saml2/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        String ct = req.getContentType();
        boolean formEncoded = ct != null && ct.startsWith("application/x-www-form-urlencoded");
        boolean paramsReadable = formEncoded || req.getQueryString() != null;

        log.info("SLO-TRACE >>> {} {}{} | remote={} | contentType={} | session={} | SAMLRequest={} | SAMLResponse={} | RelayState={}",
                req.getMethod(),
                req.getRequestURI(),
                req.getQueryString() != null ? "?" + req.getQueryString() : "",
                req.getRemoteAddr(),
                ct,
                session != null ? "yes(" + session.getId() + ")" : "NEMA",
                describeParam(req, "SAMLRequest", paramsReadable),
                describeParam(req, "SAMLResponse", paramsReadable),
                describeParam(req, "RelayState", paramsReadable));

        try {
            chain.doFilter(req, res);
        } finally {
            log.info("SLO-TRACE <<< {} {} -> status={} | Location={}",
                    req.getMethod(), req.getRequestURI(), res.getStatus(), res.getHeader("Location"));
        }
    }

    /** Ne dira body za ne-form sadržaj (npr. SOAP text/xml) kako se stream ne bi potrošio. */
    private static String describeParam(HttpServletRequest req, String name, boolean paramsReadable) {
        if (!paramsReadable) {
            return "n/a(body)";
        }
        String v = req.getParameter(name);
        return v == null ? "ne" : "DA(len=" + v.length() + ")";
    }
}
