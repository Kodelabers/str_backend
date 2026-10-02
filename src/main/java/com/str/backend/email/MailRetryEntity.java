package com.str.backend.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Obavijest iznajmljivaču čije slanje nije uspjelo. Nastaje tek pri prvom neuspjehu, pa
 * {@code attempts} broji i taj, inline pokušaj.
 */
@Entity
@Table(schema = "str_rn", name = "mail_retry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MailRetryEntity {

    /** Najdulji razlog koji stane u stupac {@code last_error}. */
    private static final int MAX_ERROR = 255;

    public enum Kind {
        /** {@code refId} = {@code submission_id}. */
        RN_ISSUED,
        /** {@code refId} = {@code registration_number_log.log_id}. */
        RN_LIFECYCLE
    }

    public enum Status {
        PENDING,
        SENT,
        ABANDONED
    }

    @Id
    @Column(name = "retry_id", nullable = false, updatable = false)
    private UUID retryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 32, nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "ref_id", nullable = false, updatable = false)
    private UUID refId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static MailRetryEntity firstFailure(Kind kind, UUID refId, Instant now,
                                               Instant nextAttemptAt, String error) {
        MailRetryEntity e = new MailRetryEntity();
        e.retryId = UUID.randomUUID();
        e.kind = kind;
        e.refId = refId;
        e.status = Status.PENDING;
        e.attempts = 1;
        e.nextAttemptAt = nextAttemptAt;
        e.lastError = truncate(error);
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void recordFailure(Instant now, Instant nextAttemptAt, String error) {
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
        this.updatedAt = now;
    }

    public void markSent(Instant now) {
        this.status = Status.SENT;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public void abandon(Instant now, String reason) {
        this.status = Status.ABANDONED;
        this.nextAttemptAt = null;
        this.lastError = truncate(reason);
        this.updatedAt = now;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ERROR ? value : value.substring(0, MAX_ERROR);
    }
}
