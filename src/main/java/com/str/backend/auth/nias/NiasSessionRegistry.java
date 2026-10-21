package com.str.backend.auth.nias;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
public class NiasSessionRegistry implements HttpSessionListener {

    private static final Logger log = LoggerFactory.getLogger(NiasSessionRegistry.class);
    private static final String KEY_ATTR = "__nias_session_key";

    private final ConcurrentHashMap<Key, HttpSession> sessions = new ConcurrentHashMap<>();

    public void register(String nameId, String sessionIndex, HttpSession session) {
        if (nameId == null || sessionIndex == null || session == null) return;
        Key key = new Key(nameId, sessionIndex);
        // U sesiju ide ArrayList dva Stringa, ne Key: sesija je zajednička sa str-internal-api
        // (session.spring_session), a servis koji našu klasu nema na takvom atributu puca.
        session.setAttribute(KEY_ATTR, new ArrayList<>(List.of(nameId, sessionIndex)));
        sessions.put(key, session);
        // TODO(SLO-debug): PRIVREMENO — ukloniti nakon dijagnostike.
        log.info("SLO-TRACE registry: zapamćen sessionIndex={} (ukupno praćenih sjednica={})",
                sessionIndex, sessions.size());
    }

    public boolean invalidate(String nameId, String sessionIndex) {
        if (nameId == null || sessionIndex == null) return false;
        HttpSession session = sessions.remove(new Key(nameId, sessionIndex));
        // TODO(SLO-debug): PRIVREMENO — ukloniti nakon dijagnostike.
        log.info("SLO-TRACE registry: invalidate sessionIndex={} -> lokalna sjednica {}",
                sessionIndex, session != null ? "PRONAĐENA (gasim)" : "nije pronađena");
        if (session == null) return false;
        try {
            session.invalidate();
            return true;
        } catch (IllegalStateException alreadyInvalidated) {
            return true;
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        Object attr = se.getSession().getAttribute(KEY_ATTR);
        if (attr instanceof List<?> l && l.size() == 2 && l.get(0) instanceof String nameId
                && l.get(1) instanceof String sessionIndex) {
            sessions.remove(new Key(nameId, sessionIndex));
        } else if (attr instanceof Key key) {
            sessions.remove(key);
        }
    }

    /**
     * Ključ mape u memoriji. {@link Serializable} ostaje samo da se sesije spremljene prije
     * prelaska na {@code ArrayList} i dalje mogu učitati; nakon što isteknu, može se ukloniti.
     */
    public record Key(String nameId, String sessionIndex) implements Serializable {
        public Key {
            Objects.requireNonNull(nameId);
            Objects.requireNonNull(sessionIndex);
        }
    }
}
