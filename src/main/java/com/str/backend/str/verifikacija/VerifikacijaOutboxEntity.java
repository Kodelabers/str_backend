package com.str.backend.str.verifikacija;

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
 * Zahtjev za žurnu verifikaciju koji STR duguje eTurizmu za jedan izdani RB
 * ({@code str_rn.eturizam_verifikacija}, changeset 150). Nastaje u transakciji izdavanja RB-a,
 * a zatvara ga {@link EturizamVerifikacijaWriter} kad upiše (ili zatekne) zahtjev u
 * {@code str.verification_request}.
 */
@Entity
@Table(schema = "str_rn", name = "eturizam_verifikacija")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerifikacijaOutboxEntity {

    /** Najdulji razlog koji stane u stupac {@code last_error}. */
    static final int MAX_ERROR = 500;

    public enum Status {
        /** Čeka upis u eTurizam (prvi pokušaj ili retry). */
        PENDING,
        /** STR je upisao zahtjev u {@code str.verification_request} (i obavijesti, ako su uključene). */
        ZAHTJEV_UPISAN,
        /** Zahtjev za dokument je već postojao — STR ga nije dirao ni obavještavao službenike. */
        ZAHTJEV_POSTOJAO,
        /** Pokušaji su iscrpljeni; ostaje za ručnu intervenciju. */
        ABANDONED
    }

    @Id
    @Column(name = "verifikacija_id", nullable = false, updatable = false)
    private UUID verifikacijaId;

    @Column(name = "rn", length = 20, nullable = false, updatable = false, unique = true)
    private String rn;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Column(name = "facility_id", length = 64, nullable = false, updatable = false)
    private String facilityId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private Status status;

    @Column(name = "verification_request_id")
    private Long verificationRequestId;

    @Column(name = "obavijesti_poslano")
    private Integer obavijestiPoslano;

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

    /**
     * @param nextAttemptAt kad ga smije uzeti retry job — kasnije od inline pokušaja nakon commita,
     *                      da ga job ne preteče
     */
    public static VerifikacijaOutboxEntity pending(String rn, UUID submissionId, String facilityId,
                                                   long documentId, Instant now, Instant nextAttemptAt) {
        VerifikacijaOutboxEntity e = new VerifikacijaOutboxEntity();
        e.verifikacijaId = UUID.randomUUID();
        e.rn = rn;
        e.submissionId = submissionId;
        e.facilityId = facilityId;
        e.documentId = documentId;
        e.status = Status.PENDING;
        e.attempts = 0;
        e.nextAttemptAt = nextAttemptAt;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    /** Zahtjev je upisan ili zatečen; {@code obavijesti} je broj upisanih obavijesti (0 kad ih nema). */
    public void complete(Status ishod, long verificationRequestId, int obavijesti, Instant now) {
        if (ishod != Status.ZAHTJEV_UPISAN && ishod != Status.ZAHTJEV_POSTOJAO) {
            throw new IllegalArgumentException("nije završni ishod upisa: " + ishod);
        }
        this.status = ishod;
        this.verificationRequestId = verificationRequestId;
        this.obavijestiPoslano = obavijesti;
        this.nextAttemptAt = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void recordFailure(Instant now, Instant nextAttemptAt, String error) {
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
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
