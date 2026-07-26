package com.str.backend.egop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Politika ponavljanja urudžbiranja — dijele je {@link EgopRegistrationDispatcher}
 * (računa kad smije sljedeći pokušaj) i {@link EgopRetryJob} (odlučuje tko je kandidat),
 * pa granica pokušaja živi na jednom mjestu.
 *
 * <p>Backoff je eksponencijalan s gornjom granicom. Bez njega bi se svih 10 pokušaja
 * potrošilo unutar ~20 minuta (cron svake 2 min), pa bi i kratkotrajni ispad eGOP-a
 * trajno ostavio registraciju u {@code FAILED}.
 */
@Component
public class EgopRetryPolicy {

    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration backoffMax;

    public EgopRetryPolicy(@Value("${str.egop.retry.max-attempts:10}") int maxAttempts,
                           @Value("${str.egop.retry.backoff-base:PT2M}") Duration backoffBase,
                           @Value("${str.egop.retry.backoff-max:PT2H}") Duration backoffMax) {
        this.maxAttempts = maxAttempts;
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /**
     * @param attemptsSoFar broj pokušaja <b>prije</b> onog koji je upravo pao
     * @return kad retry job smije pokušati ponovo
     */
    public Instant nextAttemptAt(Instant now, int attemptsSoFar) {
        return now.plus(backoffFor(attemptsSoFar));
    }

    private Duration backoffFor(int attemptsSoFar) {
        if (attemptsSoFar <= 0) {
            return backoffBase;
        }
        // 2^attemptsSoFar preko shifta; ograničeno na 30 da se izbjegne prelijevanje
        // kod apsurdno visokog max-attempts.
        long multiplier = 1L << Math.min(attemptsSoFar, 30);
        Duration backoff = backoffBase.multipliedBy(multiplier);
        return backoff.compareTo(backoffMax) > 0 ? backoffMax : backoff;
    }
}
