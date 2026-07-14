package com.str.backend.auth.nias;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.Serializable;
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
        session.setAttribute(KEY_ATTR, key);
        sessions.put(key, session);
    }

    public boolean invalidate(String nameId, String sessionIndex) {
        if (nameId == null || sessionIndex == null) return false;
        HttpSession session = sessions.remove(new Key(nameId, sessionIndex));
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
        if (attr instanceof Key key) {
            sessions.remove(key);
        }
    }

    public record Key(String nameId, String sessionIndex) implements Serializable {
        public Key {
            Objects.requireNonNull(nameId);
            Objects.requireNonNull(sessionIndex);
        }
    }
}
