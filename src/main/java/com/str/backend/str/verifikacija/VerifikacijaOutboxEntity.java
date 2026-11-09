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
 * Žurna verifikacija koju STR duguje eTurizmu za jedan RB ({@code str_rn.eturizam_verifikacija},
 * changeseti 151, 153, 154), po {@link Vrsta}:
 * <ul>
 *   <li>{@link Vrsta#ETURIZAM_OBJEKT} — RB izdan za neverificiran objekt iz eTurizma. Nastaje u
 *       transakciji izdavanja RB-a, a zatvara ga {@link EturizamVerifikacijaWriter} kad upiše (ili
 *       zatekne) zahtjev u {@code str.verification_request}.</li>
 *   <li>{@link Vrsta#NOVI_OBJEKT_RJESENJE} — uz RB novog objekta predano je rješenje o kategorizaciji.
 *       Objekta u eTurizmu nema, pa ni dokumenta uz koji bi se upisao zahtjev: šalju se samo obavijesti
 *       službenicima nadležne županije. Nastaje u transakciji predaje rješenja.</li>
 * </ul>
 */
@Entity
@Table(schema = "str_rn", name = "eturizam_verifikacija")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerifikacijaOutboxEntity {

    /** Najdulji razlog koji stane u stupac {@code last_error}. */
    static final int MAX_ERROR = 500;

    /** Najdulji naziv županije ({@code accommodation.county}). */
    static final int MAX_ZUPANIJA = 128;

    public enum Vrsta {
        /** Neverificiran objekt iz eTurizma: zahtjev uz njegov dokument i obavijesti nadležnoj jedinici. */
        ETURIZAM_OBJEKT,
        /** Novi objekt uz predano rješenje o kategorizaciji: samo obavijesti službenicima županije. */
        NOVI_OBJEKT_RJESENJE
    }

    public enum Status {
        /** Čeka upis u eTurizam (prvi pokušaj ili retry). */
        PENDING,
        /** STR je upisao zahtjev u {@code str.verification_request} (i obavijesti, ako su uključene). */
        ZAHTJEV_UPISAN,
        /**
         * Novi objekt ({@link Vrsta#NOVI_OBJEKT_RJESENJE}): obavijesti su upisane, bez zahtjeva. Broj je u
         * {@code obavijesti_poslano} — 0 kad županija nema službenika ili se ne prepozna (WARN u logu).
         */
        OBAVIJESTI_UPISANE,
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
         * verificiran, rješenje novog objekta je već obrađeno, ili RB-a nema. Ništa se ne upisuje;
         * razlog je u {@code last_error}.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "vrsta", length = 32, nullable = false, updatable = false)
    private Vrsta vrsta;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    /** {@code str.facility.id}; samo {@link Vrsta#ETURIZAM_OBJEKT}. */
    @Column(name = "facility_id", length = 64, updatable = false)
    private String facilityId;

    /** {@code str.document.id} = {@code source_document_id} zahtjeva; samo {@link Vrsta#ETURIZAM_OBJEKT}. */
    @Column(name = "document_id", updatable = false)
    private Long documentId;

    /**
     * Predano rješenje ({@code str_rn.categorization_decision}); samo {@link Vrsta#NOVI_OBJEKT_RJESENJE}.
     * Mijenja se kad redak bez poslane obavijesti dobije novo rješenje ({@link #ponovoNaCekanje}).
     */
    @Column(name = "decision_id")
    private UUID decisionId;

    /**
     * Županija objekta iz STR-a ({@code accommodation.county}) — po njoj se biraju primatelji;
     * samo {@link Vrsta#NOVI_OBJEKT_RJESENJE}.
     */
    @Column(name = "zupanija", length = MAX_ZUPANIJA, updatable = false)
    private String zupanija;

    /**
     * Naziv objekta: iz eTurizma pri izdavanju RB-a ({@code findOwnership}) za objekt iz eTurizma, a iz
     * zahtjeva za RB za novi objekt; {@code null} kad ga nema.
     */
    @Column(name = "objekt_naziv", length = 255, updatable = false)
    private String objektNaziv;

    /** Naselje (ili općina) objekta, iz istog izvora kao naziv. */
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
        VerifikacijaOutboxEntity e = novi(Vrsta.ETURIZAM_OBJEKT, rn, submissionId, objektNaziv, objektMjesto,
                now, nextAttemptAt);
        e.facilityId = facilityId;
        e.documentId = documentId;
        return e;
    }

    /**
     * Redak za novi objekt uz predano rješenje ({@link Vrsta#NOVI_OBJEKT_RJESENJE}).
     *
     * @param nextAttemptAt kao kod {@link #pending}: kasnije od inline pokušaja nakon commita predaje
     */
    public static VerifikacijaOutboxEntity pendingNoviObjekt(String rn, UUID submissionId, UUID decisionId,
                                                             String zupanija, String objektNaziv,
                                                             String objektMjesto, Instant now,
                                                             Instant nextAttemptAt) {
        VerifikacijaOutboxEntity e = novi(Vrsta.NOVI_OBJEKT_RJESENJE, rn, submissionId, objektNaziv, objektMjesto,
                now, nextAttemptAt);
        e.decisionId = decisionId;
        e.zupanija = truncate(zupanija, MAX_ZUPANIJA);
        return e;
    }

    private static VerifikacijaOutboxEntity novi(Vrsta vrsta, String rn, UUID submissionId, String objektNaziv,
                                                 String objektMjesto, Instant now, Instant nextAttemptAt) {
        VerifikacijaOutboxEntity e = new VerifikacijaOutboxEntity();
        e.verifikacijaId = UUID.randomUUID();
        e.vrsta = vrsta;
        e.rn = rn;
        e.submissionId = submissionId;
        e.objektNaziv = truncate(objektNaziv, 255);
        e.objektMjesto = truncate(objektMjesto, 255);
        e.status = Status.PENDING;
        e.attempts = 0;
        e.nextAttemptAt = nextAttemptAt;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    /** Novi objekt: obavijesti su upisane ({@code obavijesti} je njihov broj, 0 kad primatelja nema). */
    public void completeObavijesti(int obavijesti, Instant now) {
        if (vrsta != Vrsta.NOVI_OBJEKT_RJESENJE) {
            throw new IllegalStateException("obavijesti bez zahtjeva samo za novi objekt, ne " + vrsta);
        }
        this.status = Status.OBAVIJESTI_UPISANE;
        this.obavijestiPoslano = obavijesti;
        this.nextAttemptAt = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * Novi objekt, obavijest još nije poslana (redak čeka, preskočen jer je prethodno rješenje obrađeno,
     * ili napušten), a predano je novo rješenje: redak se vraća na čekanje uz njega, s punim brojem pokušaja.
     */
    public void ponovoNaCekanje(UUID decisionId, Instant now, Instant nextAttemptAt) {
        if (vrsta != Vrsta.NOVI_OBJEKT_RJESENJE || status == Status.OBAVIJESTI_UPISANE) {
            throw new IllegalStateException("ponovno čekanje samo za novi objekt bez poslane obavijesti, ne "
                    + vrsta + "/" + status);
        }
        this.decisionId = decisionId;
        this.status = Status.PENDING;
        this.attempts = 0;
        this.obavijestiPoslano = null;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = null;
        this.updatedAt = now;
    }

    public boolean isNoviObjekt() {
        return vrsta == Vrsta.NOVI_OBJEKT_RJESENJE;
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
