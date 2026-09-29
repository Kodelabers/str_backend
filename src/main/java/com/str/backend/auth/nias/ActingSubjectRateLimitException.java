package com.str.backend.auth.nias;

/** Previše odabira pravne osobe u kratkom vremenu (429); {@link #retryAfterSeconds()} do sljedećeg dopuštenog. */
public class ActingSubjectRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public ActingSubjectRateLimitException(long retryAfterSeconds) {
        super("previše odabira pravne osobe, ponovo za " + retryAfterSeconds + " s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
