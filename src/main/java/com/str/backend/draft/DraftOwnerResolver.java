package com.str.backend.draft;

import com.str.backend.auth.LessorPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;

@Component
public class DraftOwnerResolver {

    private static final String MOCK_NIAS_COOKIE = "mock_nias_oib";
    private static final int MOCK_COOKIE_MAX_AGE_SECONDS = (int) Duration.ofDays(90).toSeconds();
    private final SecureRandom random = new SecureRandom();

    public DraftOwner resolve(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof LessorPrincipal lessor) {
            return new DraftOwner(DraftOwnerType.LESSOR, lessor.getLessorId().toString());
        }
        // NIAS/eIDAS auth not yet implemented — fall back to a per-browser mock OIB
        // stored in a cookie so each browser sees a stable identity in demo flows.
        String mockOib = readMockOib(request);
        if (mockOib == null) {
            mockOib = generateMockOib();
            Cookie cookie = new Cookie(MOCK_NIAS_COOKIE, mockOib);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(MOCK_COOKIE_MAX_AGE_SECONDS);
            response.addCookie(cookie);
        }
        return new DraftOwner(DraftOwnerType.NIAS_OIB, mockOib);
    }

    private String readMockOib(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (MOCK_NIAS_COOKIE.equals(c.getName()) && c.getValue() != null && c.getValue().matches("\\d{11}")) {
                return c.getValue();
            }
        }
        return null;
    }

    private String generateMockOib() {
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 11; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
