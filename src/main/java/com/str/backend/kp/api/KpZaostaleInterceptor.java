package com.str.backend.kp.api;

import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.CountyScopeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Šalje zaostale poruke u KP (T16) sesijom županijskog službenika koji upravo radi u STR-u.
 *
 * <p>KP API prima samo NIAS sesiju, a nje nema u noćnom poslu ni kod ponavljanja nakon greške. Zato
 * takve poruke čekaju i šalje ih sljedeći zahtjev službenika <b>iste županije</b> na internom API-ju.
 * Službenik ministarstva ne šalje županijske poruke.
 *
 * <p>Ne usporava zahtjev: prvo jeftina provjera ima li ijedne slobodne poruke, a tek onda županija
 * službenika; samo slanje ide u pool. Ista sesija pokreće slanje najviše jednom u {@link #RAZMAK}.
 * Nikad ne baca i nikad ne odbija zahtjev.
 */
class KpZaostaleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(KpZaostaleInterceptor.class);

    static final Duration RAZMAK = Duration.ofMinutes(1);
    private static final int MAX_PRACENIH = 1_000;

    private final KpPorukaService service;
    private final KpPorukaRepository repository;
    private final CountyScopeService countyScope;
    private final KpApiProperties properties;
    private final Clock clock;
    /** Zadnje pokretanje po sesiji (ključ je hash ID-a sesije, ne sam ID). */
    private final Map<Integer, Long> zadnje = new ConcurrentHashMap<>();

    KpZaostaleInterceptor(KpPorukaService service, KpPorukaRepository repository, CountyScopeService countyScope,
                          KpApiProperties properties, Clock clock) {
        this.service = service;
        this.repository = repository;
        this.countyScope = countyScope;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            pokreni(request);
        } catch (RuntimeException e) {
            log.warn("kp_api_zaostale_failed path={} — zaostale poruke nisu pokrenute, zahtjev ide dalje",
                    request.getRequestURI(), e);
        }
        return true;
    }

    private void pokreni(HttpServletRequest request) {
        if (!properties.aktivno()) {
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        long sada = clock.millis();
        int kljuc = session.getId().hashCode();
        Long prije = zadnje.get(kljuc);
        if (prije != null && sada - prije < RAZMAK.toMillis()) {
            return;
        }
        if (!repository.postojeSlobodne(clock.instant())) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return;
        }
        zabiljeziPokretanje(kljuc, sada);
        CountyScope scope = countyScope.scopeFor(auth);
        if (scope.outcome() == CountyScope.Outcome.COUNTY && scope.zuRb() != null) {
            service.posaljiZaostale(scope.zuRb(), session.getId());
        }
    }

    private void zabiljeziPokretanje(int kljuc, long sada) {
        if (zadnje.size() > MAX_PRACENIH) {
            zadnje.values().removeIf(t -> sada - t >= RAZMAK.toMillis());
        }
        zadnje.put(kljuc, sada);
    }
}
