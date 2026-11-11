package com.str.backend.kp.api;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/** ID postojeće sesije tekućeg HTTP zahtjeva; prazno izvan zahtjeva (noćni posao) ili bez sesije. */
final class KpSesija {

    private KpSesija() {
    }

    static Optional<String> trenutna() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes s)) {
            return Optional.empty();
        }
        HttpSession session = s.getRequest().getSession(false);
        return session == null ? Optional.empty() : Optional.of(session.getId());
    }
}
