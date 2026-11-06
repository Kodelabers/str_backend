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
 * ({@code str_rn.eturizam_verifikacija}, changeset 151). Nastaje u transakciji izdavanja RB-a,
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
        /**
         * Zahtjev za dokument je već postojao — upisao ga je eTurizam ili STR za drugi RB istog dokumenta
         * (zahtjev je po dokumentu). Ne mijenja se i obavijesti se ne šalju; zapisuje se njegov id.
         *
         * <p>Vlastiti zahtjev istog retka ovdje ne može završiti: zahtjev i zatvaranje retka su u istoj
         * transakciji, pa i kad ishod commita nije poznat, ili je prošlo oboje (redak je zatvoren i
         * sljedeći pokušaj ga preskače) ili ništa.
         */
        ZAHTJEV_POSTOJAO,
        /**
         * Upis više nema smisla: RB je u međuvremenu opozvan ili povučen, objekt je u međuvremenu
         * verificiran, ili RB-a nema. Ništa se ne upisuje; razlog je u {@code last_error}.
         */
        PRESKOCENO,
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

    /** Naziv objekta iz eTurizma pri izdavanju RB-a ({@code findOwnership}); {@code null} kad ga nema. */
    @Column(name = "objekt_naziv", length = 255, updatable = false)
    private String objektNaziv;

    /** Naselje (ili općina) objekta iz eTurizma pri izdavanju RB-a. */
    @Column(name = "objekt_mjesto", length = 255, updatable = false)
    private String objektMjesto;

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
                                                   long documentId, String objektNaziv, String objektMjesto,
                                                   Instant now, Instant nextAttemptAt) {
        VerifikacijaOutboxEntity e = new VerifikacijaOutboxEntity();
        e.verifikacijaId = UUID.randomUUID();
        e.rn = rn;
        e.submissionId = submissionId;
        e.facilityId = facilityId;
        e.documentId = documentId;
        e.objektNaziv = truncate(objektNaziv, 255);
        e.objektMjesto = truncate(objektMjesto, 255);
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

    /** Upis više nema smisla ({@link Status#PRESKOCENO}); ništa nije upisano. */
    public void skip(Instant now, String reason) {
        this.status = Status.PRESKOCENO;
        this.nextAttemptAt = null;
        this.lastError = truncate(reason);
        this.updatedAt = now;
    }

    /**
     * Nedostaje GRANT: pokušaj se <b>ne broji</b> (inače bi redak bez krivnje otišao u ABANDONED), samo
     * se odgodi. Redak ostaje {@code PENDING}.
     */
    public void postpone(Instant now, Instant nextAttemptAt, String reason) {
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(reason);
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
        return truncate(value, MAX_ERROR);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
