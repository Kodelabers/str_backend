package com.str.backend.egop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Otprema jednog izlaznog pismena u korisnički pretinac (KP) eGrađana.
 *
 * <p>Odvojena od {@link EgopPismenoEntity} jer je otprema vlastita jedinica posla: pismeno može
 * biti urudžbirano, a otprema pasti (primatelj nema aktiviran KP, eGOP odbije), i pad jednoga ne
 * smije mijenjati status drugoga.
 *
 * <p>Redak nastaje zajedno s izlaznim pismenom ({@link Status#PENDING}), i to samo dok je KP
 * uključen, a šalje se tek kad je pismeno urudžbirano i ima priložen dokument — vidi
 * {@link EgopKpOtpremaService}. Akt urudžbiran prekasno ({@link #aktNastaoAt} stariji od
 * {@code str.egop.kp.max-akt-age}) ide u {@link Status#SKIPPED} i ne šalje se.
 *
 * <p>{@code kp*} polja su ono što eGOP vrati o poruci ({@code DohvatiKPStatusPorukeSaVremenima}).
 * Samo se zapisuju; na API-ju se ne izlažu.
 */
@Entity
@Table(schema = "str_rn", name = "egop_kp_otprema",
        // Isti ključ kao u changesetu 139 — na test profilu shemu radi Hibernate iz entiteta.
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_egop_kp_otprema_pismeno", columnNames = "pismeno_id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class EgopKpOtpremaEntity {

    /**
     * {@code SKIPPED} je konačan kao i {@code SENT}: akt je zastario prije nego što je urudžbiran
     * pa se ne dostavlja, da stranci ne otvori rok u postupku koji je u međuvremenu završen.
     */
    public enum Status { PENDING, SENT, FAILED, SKIPPED }

    /** Primatelj je potvrdio primitak. */
    public static final int KP_POTVRDEN_PRIMITAK = 2;
    /** Primatelj je odbio primitak ili suglasnost za elektroničku dostavu. */
    public static final int KP_ODBIJENO = 3;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "pismeno_id", nullable = false, updatable = false)
    private UUID pismenoId;

    @Column(name = "primatelj_oib", length = 11, nullable = false, updatable = false)
    private String primateljOib;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Status status;

    /** Kad je akt nastao; za obavijest o dodjeli trenutak izdavanja RB-a. */
    @Column(name = "akt_nastao_at", nullable = false, updatable = false)
    private Instant aktNastaoAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "error")
    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "kp_status_poruke")
    private Integer kpStatusPoruke;

    @Column(name = "kp_kreirano_at")
    private Instant kpKreiranoAt;

    @Column(name = "kp_poslano_at")
    private Instant kpPoslanoAt;

    @Column(name = "kp_zaprimljeno_nias_at")
    private Instant kpZaprimljenoNiasAt;

    @Column(name = "kp_proslijedeno_at")
    private Instant kpProslijedenoAt;

    @Column(name = "kp_zaprimljeno_primatelj_at")
    private Instant kpZaprimljenoPrimateljAt;

    @Column(name = "kp_zadnja_provjera_at")
    private Instant kpZadnjaProvjeraAt;

    @Column(name = "kp_zadnja_aktivnost_at")
    private Instant kpZadnjaAktivnostAt;

    @Column(name = "kp_status_checked_at")
    private Instant kpStatusCheckedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static EgopKpOtpremaEntity pending(UUID pismenoId, String primateljOib, Instant aktNastaoAt) {
        EgopKpOtpremaEntity e = new EgopKpOtpremaEntity();
        e.id = UUID.randomUUID();
        e.pismenoId = pismenoId;
        e.primateljOib = primateljOib;
        e.aktNastaoAt = aktNastaoAt;
        e.status = Status.PENDING;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    public void markSent(Instant sentAt) {
        this.status = Status.SENT;
        this.sentAt = sentAt;
        this.error = null;
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void markSkipped(String reason) {
        this.status = Status.SKIPPED;
        this.error = reason;
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error, Instant nextAttemptAt) {
        this.status = Status.FAILED;
        this.error = error;
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = Instant.now();
    }

    /** Zapis onoga što je eGOP vratio o poruci u KP-u; {@code null} vrijednosti ne brišu zapisane. */
    public void applyKpStatus(KpStatusPoruke kp, Instant checkedAt) {
        this.kpStatusPoruke = kp.statusPoruke();
        this.kpKreiranoAt = prefer(kp.kreirano(), kpKreiranoAt);
        this.kpPoslanoAt = prefer(kp.poslano(), kpPoslanoAt);
        this.kpZaprimljenoNiasAt = prefer(kp.zaprimljenoNias(), kpZaprimljenoNiasAt);
        this.kpProslijedenoAt = prefer(kp.proslijedenoPrimatelju(), kpProslijedenoAt);
        this.kpZaprimljenoPrimateljAt = prefer(kp.zaprimljenoPrimatelj(), kpZaprimljenoPrimateljAt);
        this.kpZadnjaProvjeraAt = prefer(kp.zadnjaProvjeraAktivnosti(), kpZadnjaProvjeraAt);
        this.kpZadnjaAktivnostAt = prefer(kp.zadnjaAktivnost(), kpZadnjaAktivnostAt);
        this.kpStatusCheckedAt = checkedAt;
        this.updatedAt = Instant.now();
    }

    /** Poslano ili namjerno preskočeno — u oba slučaja nema više što slati. */
    public boolean isDone() {
        return status == Status.SENT || status == Status.SKIPPED;
    }

    private static Instant prefer(Instant fresh, Instant stored) {
        return fresh != null ? fresh : stored;
    }
}
