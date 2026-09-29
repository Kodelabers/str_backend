package com.str.backend.auth.nias;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ograničenje odabira pravne osobe ({@code POST /api/nias/acting-subject}). Svaki odabir ide FINA-i
 * (trošak, spor odgovor), a odgovori „tvrtka ne postoji" i „niste zastupnik" se razlikuju — bez
 * ograničenja endpoint bi služio i za ispitivanje koji OIB-ovi pripadaju postojećim tvrtkama.
 *
 * <p>Klizni prozor kao {@code RegisterRateLimitFilter}, ali po NIAS osobi i po sesiji umjesto po IP-u,
 * i pozvan tek iza lokalne provjere OIB-a — neispravan ili vlastiti OIB ne troši kvotu. Stanje je u
 * memoriji jedne instance; sve okoline imaju po jedan backend.
 *
 * <p>Pozitivan odgovor se namjerno ne kešira: ponovni odabir iste tvrtke u kratkom vremenu je rijedak,
 * pa bi keš malo uštedio, a uveo bi zastarjelo „zastupa" koje bi vrijedilo do isteka keša.
 */
@Component
public class ActingSubjectRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ActingSubjectRateLimiter.class);

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);
    /** Iznad ovoliko ključeva čiste se prazni, da mapa ne raste s brojem ikad viđenih sesija. */
    private static final int CLEANUP_THRESHOLD = 1_000;

    private final int perMinute;
    private final int perHour;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new HashMap<>();

    public ActingSubjectRateLimiter(@Value("${app.eovlastenja.rate-limit.per-minute:10}") int perMinute,
                                    @Value("${app.eovlastenja.rate-limit.per-hour:50}") int perHour,
                                    Clock clock) {
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.clock = clock;
    }

    /**
     * Bilježi jedan odabir za osobu i sesiju. Kad je bilo koja od njih iznad granice, odabir se ne
     * bilježi i baca se {@link ActingSubjectRateLimitException} s vremenom do sljedećeg dopuštenog.
     */
    public synchronized void acquire(String personOib, String sessionId) {
        Instant now = clock.instant();
        List<String> keys = new ArrayList<>(2);
        keys.add("person:" + personOib);
        if (sessionId != null) {
            keys.add("session:" + sessionId);
        }
        long retryAfter = 0;
        for (String key : keys) {
            Deque<Instant> window = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
            prune(window, now);
            retryAfter = Math.max(retryAfter, retryAfter(window, now));
        }
        if (retryAfter > 0) {
            log.info("acting_subject_rate_limited retry_after_s={}", retryAfter);
            throw new ActingSubjectRateLimitException(retryAfter);
        }
        for (String key : keys) {
            attempts.get(key).addLast(now);
        }
        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.values().forEach(w -> prune(w, now));
            attempts.values().removeIf(Deque::isEmpty);
        }
    }

    /** Sekunde do prvog dopuštenog pokušaja; 0 kad je pokušaj dopušten sada. */
    private long retryAfter(Deque<Instant> window, Instant now) {
        long wait = 0;
        if (window.size() >= perHour) {
            wait = secondsUntil(window.peekFirst().plus(HOUR), now);
        }
        Instant minuteAgo = now.minus(MINUTE);
        List<Instant> lastMinute = window.stream().filter(t -> t.isAfter(minuteAgo)).toList();
        if (lastMinute.size() >= perMinute) {
            wait = Math.max(wait, secondsUntil(lastMinute.get(lastMinute.size() - perMinute).plus(MINUTE), now));
        }
        return wait;
    }

    private static void prune(Deque<Instant> window, Instant now) {
        Instant hourAgo = now.minus(HOUR);
        while (!window.isEmpty() && !window.peekFirst().isAfter(hourAgo)) {
            window.pollFirst();
        }
    }

    private static long secondsUntil(Instant moment, Instant now) {
        long millis = Duration.between(now, moment).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
