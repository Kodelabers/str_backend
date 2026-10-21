package com.str.backend.kp;

import com.str.backend.kp.client.KpAktivnost;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Dostava jednog izlaznog pismena u korisnički pretinac (KP) — izravno, preko SZP-a APIS IT-a.
 *
 * <p>Odvojena od {@code EgopPismenoEntity} jer je dostava vlastita jedinica posla: pismeno može
 * biti urudžbirano, a dostava pasti, i pad jednoga ne smije mijenjati status drugoga.
 *
 * <p>Redak nastaje zajedno s izlaznim pismenom ({@link Status#PENDING}), i to samo dok je KP
 * uključen, a šalje se tek kad je pismeno urudžbirano s dokumentom — vidi {@link KpOtpremaService}.
 * {@link #idPoruke} nastaje odmah i ne mijenja se: svaki pokušaj nosi isti, pa SZP ponovljenu
 * poruku odbija kao duplikat (K009/Z012) umjesto da je dostavi dvaput.
 *
 * <p>{@code kp*} polja su ono što KP vrati o poruci s pravnim učinkom (servisi 128/129). Samo se
 * zapisuju; na API-ju se ne izlažu.
 */
@Entity
@Table(schema = "str_rn", name = "kp_otprema",
        // Isti ključevi kao u changesetu 140 — na test profilu shemu radi Hibernate iz entiteta.
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_kp_otprema_pismeno", columnNames = "pismeno_id"),
                @UniqueConstraint(name = "uq_kp_otprema_id_poruke", columnNames = "id_poruke")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KpOtpremaEntity {

    /**
     * {@code SKIPPED} (akt zastario prije urudžbe) i {@code NEMA_PRETINCA} (primatelj nema aktivan
     * KP, akt ide e-poštom) konačni su kao i {@code SENT}.
     */
    public enum Status { PENDING, SENT, FAILED, SKIPPED, NEMA_PRETINCA }

    /** Primatelj je potvrdio primitak. */
    public static final int KP_POTVRDEN_PRIMITAK = 2;
    /** Primatelj je odbio primitak ili suglasnost za elektroničku dostavu. */
    public static final int KP_ODBIJENO = 3;

    /** {@code error} je TEXT u bazi; granica je tu da poruka SZP-a s puno grešaka ne pukne upis. */
    static final int MAX_ERROR = 4000;
    static final int MAX_ERROR_CODE = 64;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "pismeno_id", nullable = false, updatable = false)
    private UUID pismenoId;

    @Column(name = "id_poruke", nullable = false, updatable = false)
    private UUID idPoruke;

    @Enumerated(EnumType.STRING)
    @Column(name = "primatelj_tip", length = 2, nullable = false, updatable = false)
    private KpPrimatelj.Tip primateljTip;

    /** OIB građana (FO) ili IPS poslovnog subjekta (PS). */
    @Column(name = "primatelj_id", length = 30, nullable = false, updatable = false)
    private String primateljId;

    @Column(name = "izvor_registar", length = 3, updatable = false)
    private String izvorRegistar;

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

    @Column(name = "error", length = MAX_ERROR)
    private String error;

    /** Šifre odbijanja zadnjeg pokušaja (M…/K…/Z…), zarezom odvojene. */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    /**
     * {@code GsbId} zadnjeg poziva — identifikator koji SZP dodjeljuje poruci; po njemu (i po GUID-u
     * greške u {@link #error}) helpdesk APIS-a traži incident.
     */
    @Column(name = "gsb_id", length = 36)
    private String gsbId;

    /** Tip registrirane e-poruke s kojim je poruka poslana. */
    @Column(name = "tip_poruke")
    private Integer tipPoruke;

    /** Je li poslana kao poruka s pravnim učinkom — samo se za takve dohvaća aktivnost. */
    @Column(name = "pravni_ucinak", nullable = false)
    private boolean pravniUcinak;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "kp_status_aktivnosti")
    private Integer kpStatusAktivnosti;

    @Column(name = "kp_primitak_at")
    private Instant kpPrimitakAt;

    @Column(name = "kp_aktivnost_at")
    private Instant kpAktivnostAt;

    @Column(name = "kp_istek_roka_at")
    private Instant kpIstekRokaAt;

    /** Potpisani PDF potvrde primitka iz KP-a — dokaz dostave. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "kp_potvrda_pdf", length = 10_485_760)
    private byte[] kpPotvrdaPdf;

    @Column(name = "kp_status_checked_at")
    private Instant kpStatusCheckedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static KpOtpremaEntity pending(UUID pismenoId, KpPrimatelj primatelj, Instant aktNastaoAt) {
        KpOtpremaEntity e = new KpOtpremaEntity();
        e.id = UUID.randomUUID();
        e.pismenoId = pismenoId;
        e.idPoruke = UUID.randomUUID();
        e.primateljTip = primatelj.tip();
        e.primateljId = primatelj.id();
        e.izvorRegistar = primatelj.izvorRegistar();
        e.aktNastaoAt = aktNastaoAt;
        e.status = Status.PENDING;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    public KpPrimatelj primatelj() {
        return new KpPrimatelj(primateljTip, primateljId, izvorRegistar);
    }

    public void markSent(Instant sentAt, int tipPoruke, boolean pravniUcinak, String gsbId) {
        this.status = Status.SENT;
        this.gsbId = gsbId;
        this.sentAt = sentAt;
        this.tipPoruke = tipPoruke;
        this.pravniUcinak = pravniUcinak;
        this.error = null;
        this.errorCode = null;
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void markSkipped(String reason) {
        this.status = Status.SKIPPED;
        this.error = cut(reason, MAX_ERROR);
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void markNemaPretinca(String errorCode, String error, String gsbId) {
        this.status = Status.NEMA_PRETINCA;
        this.gsbId = gsbId;
        this.errorCode = cut(errorCode, MAX_ERROR_CODE);
        this.error = cut(error, MAX_ERROR);
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorCode, String error, Instant nextAttemptAt, String gsbId) {
        this.status = Status.FAILED;
        this.gsbId = gsbId;
        this.errorCode = cut(errorCode, MAX_ERROR_CODE);
        this.error = cut(error, MAX_ERROR);
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = Instant.now();
    }

    /**
     * Trajna greška: ponavljanje ne pomaže, pa se pokušaji odmah iscrpljuju i retry je više ne
     * bira. Nakon ispravka konfiguracije otpremu treba ručno vratiti ({@code attempts = 0}).
     */
    public void markExhausted(String errorCode, String error, int maxAttempts, String gsbId) {
        this.status = Status.FAILED;
        this.gsbId = gsbId;
        this.errorCode = cut(errorCode, MAX_ERROR_CODE);
        this.error = cut(error, MAX_ERROR);
        this.attempts = Math.max(attempts + 1, maxAttempts);
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    /** Zapis aktivnosti iz KP-a; {@code null} vrijednosti ne brišu zapisane. */
    public void applyAktivnost(KpAktivnost a, Instant checkedAt) {
        if (a.status() != null) {
            this.kpStatusAktivnosti = a.status();
        }
        this.kpPrimitakAt = prefer(a.primitak(), kpPrimitakAt);
        this.kpAktivnostAt = prefer(a.aktivnost(), kpAktivnostAt);
        this.kpIstekRokaAt = prefer(a.istekRoka(), kpIstekRokaAt);
        if (a.pdfPotvrde() != null && a.pdfPotvrde().length > 0 && kpPotvrdaPdf == null) {
            this.kpPotvrdaPdf = a.pdfPotvrde();
        }
        this.kpStatusCheckedAt = checkedAt;
        this.updatedAt = Instant.now();
    }

    /** Provjereno, a bez novog statusa (npr. poruka još nije vidljiva u KP-u). */
    public void markChecked(Instant checkedAt) {
        this.kpStatusCheckedAt = checkedAt;
        this.updatedAt = Instant.now();
    }

    /** Poslano, preskočeno ili preusmjereno na e-poštu — nema više što slati u KP. */
    public boolean isDone() {
        return status == Status.SENT || status == Status.SKIPPED || status == Status.NEMA_PRETINCA;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static Instant prefer(Instant fresh, Instant stored) {
        return fresh != null ? fresh : stored;
    }
}
