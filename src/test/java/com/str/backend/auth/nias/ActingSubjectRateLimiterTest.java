package com.str.backend.auth.nias;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Klizni prozor odabira pravne osobe: po osobi i po sesiji, minutna i satna granica. */
class ActingSubjectRateLimiterTest {

    private static final String PERSON = "12312312316";

    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-29T10:00:00Z"));

    @Test
    void minuteLimit_rejectsNextAttempt_andResetsAfterWindow() {
        ActingSubjectRateLimiter limiter = new ActingSubjectRateLimiter(3, 50, clock);
        for (int i = 0; i < 3; i++) {
            limiter.acquire(PERSON, "s1");
            clock.advance(Duration.ofSeconds(10));
        }

        // Prvi od tri pokušaja bio je prije 30 s — izlazi iz prozora za 30 s.
        assertThatThrownBy(() -> limiter.acquire(PERSON, "s1"))
                .isInstanceOfSatisfying(ActingSubjectRateLimitException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(30));

        clock.advance(Duration.ofSeconds(31));
        assertThatCode(() -> limiter.acquire(PERSON, "s1")).doesNotThrowAnyException();
    }

    @Test
    void hourLimit_countsAttemptsAcrossMinutes() {
        ActingSubjectRateLimiter limiter = new ActingSubjectRateLimiter(10, 5, clock);
        for (int i = 0; i < 5; i++) {
            limiter.acquire(PERSON, "s1");
            clock.advance(Duration.ofMinutes(5));
        }

        // Prvi pokušaj bio je prije 25 minuta — sljedeći je dopušten za 35 minuta.
        assertThatThrownBy(() -> limiter.acquire(PERSON, "s1"))
                .isInstanceOfSatisfying(ActingSubjectRateLimitException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(Duration.ofMinutes(35).toSeconds()));

        clock.advance(Duration.ofMinutes(36));
        assertThatCode(() -> limiter.acquire(PERSON, "s1")).doesNotThrowAnyException();
    }

    /** Nova sesija ne resetira kvotu osobe, a druga osoba ima svoju. */
    @Test
    void limitsPerPersonAndPerSession() {
        ActingSubjectRateLimiter limiter = new ActingSubjectRateLimiter(2, 50, clock);
        limiter.acquire(PERSON, "s1");
        limiter.acquire(PERSON, "s2");

        assertThatThrownBy(() -> limiter.acquire(PERSON, "s3")).isInstanceOf(ActingSubjectRateLimitException.class);
        assertThatCode(() -> limiter.acquire("70000000004", "s4")).doesNotThrowAnyException();
    }

    /** Odbijen pokušaj se ne broji — inače bi uporno ponavljanje samo produljivalo čekanje. */
    @Test
    void rejectedAttempt_isNotCounted() {
        ActingSubjectRateLimiter limiter = new ActingSubjectRateLimiter(1, 50, clock);
        limiter.acquire(PERSON, "s1");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.acquire(PERSON, "s1")).isInstanceOf(ActingSubjectRateLimitException.class);
        }

        clock.advance(Duration.ofSeconds(61));
        assertThatCode(() -> limiter.acquire(PERSON, "s1")).doesNotThrowAnyException();
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
