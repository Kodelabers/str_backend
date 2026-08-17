package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Jedan akt u eGOP predmetu submissiona.
 *
 * <p>Red nastaje <b>u trenutku kad akt nastane</b>, prije ijednog SOAP poziva — „kreiramo
 * lokalno pa šaljemo". Zato su {@code jop} i {@code urBroj} prazni dok eGOP ne odgovori, a
 * red ima vlastiti {@link EgopSyncStatus} i backoff, neovisan o statusu registracije na
 * submissionu (akt suspenzije može nastati mjesecima nakon registracije i pasti sam za sebe).
 *
 * <p>{@link #pdfContent} je snimljen dokument, ne predložak za ponovni render: retry mora
 * poslati <b>isti</b> akt. {@code RnEntity.applyStatus} briše {@code suspensionDeadline} pri
 * izlasku iz {@code SUSPENDED}, a zadnji zapis revizijskog traga se u međuvremenu promijeni —
 * ponovni render bi dao drugi sadržaj pod istim urudžbenim brojem.
 */
@Entity
@Table(schema = "str_rn", name = "egop_pismeno",
        // Isti ključ kao u changesetu 054. Deklariran i ovdje jer na test profilu shemu radi
        // Hibernate iz entiteta, a ne Liquibase — bez ovoga zaštita od dvostrukog urudžbiranja
        // (i hvatanje DataIntegrityViolationException u EgopFilingStore) nije pokrivena testom.
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_egop_pismeno_submission_vrsta_act",
                columnNames = {"submission_id", "vrsta_pismena_naziv", "act_ref"}))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class EgopPismenoEntity {

    public enum Smjer { ULAZNO, IZLAZNO }

    /** {@code act_ref} registracijskih akata — oni nemaju zapis u revizijskom tragu RB-a. */
    public static final String ACT_REF_REGISTRACIJA = "REGISTRACIJA";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    /** Registracijski broj na koji se akt odnosi; prazan za akte iz same registracije. */
    @Column(name = "rn", length = 20, updatable = false)
    private String rn;

    /**
     * Razlikuje ponovljive akte iste vrste nad istim submissionom — za akte životnog ciklusa
     * je to {@code log_id} prijelaza, pa ciklus suspenzija → reaktivacija → suspenzija daje
     * dva različita retka.
     */
    @Column(name = "act_ref", length = 64, nullable = false, updatable = false)
    private String actRef;

    @Column(name = "vrsta_pismena_naziv", length = 128, nullable = false, updatable = false)
    private String vrstaPismenaNaziv;

    @Enumerated(EnumType.STRING)
    @Column(name = "smjer", length = 8, nullable = false, updatable = false)
    private Smjer smjer;

    /** Popunjava se iz odgovora {@code KreirajPismeno2}. */
    @Column(name = "jop")
    private Integer jop;

    @Column(name = "ur_broj", length = 64)
    private String urBroj;

    /**
     * eGOP šifra vrste pismena pod kojom je akt <b>stvarno</b> urudžbiran — nije izvediva iz
     * {@link #vrstaPismenaNaziv}, jer se preslikavanje naziva na šifru konfigurira
     * ({@link EgopVrstaResolver}) i mijenja između okruženja.
     */
    @Column(name = "egop_vrsta_sifra", length = 32)
    private String egopVrstaSifra;

    /**
     * Šifra je bila posudbena. Bez ovog zapisa se nakon dolaska pravih šifri ne bi znalo
     * koje akte treba stornirati i ponovno poslati — a to je jedini put natrag, jer eGOP
     * vrstu na postojećem pismenu ne mijenja.
     */
    @Column(name = "egop_vrsta_privremena", nullable = false)
    private boolean egopVrstaPrivremena;

    /**
     * eGOP nema idempotentno prilaganje dokumenta — bez ovog flaga bi retry nakon
     * pada duplicirao PDF na već obrađenom pismenu.
     */
    @Column(name = "document_attached", nullable = false)
    private boolean documentAttached;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private EgopSyncStatus status = EgopSyncStatus.NEW;

    @Column(name = "sync_error")
    private String syncError;

    @Column(name = "sync_attempts", nullable = false)
    private int syncAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "pdf_content", length = 10_485_760)
    private byte[] pdfContent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Akt životnog ciklusa: zapisuje se prije slanja, s već renderiranim dokumentom.
     *
     * @param actRef {@code log_id} prijelaza koji je akt izazvao
     */
    public static EgopPismenoEntity forAct(UUID submissionId, String rn, String actRef,
                                           String vrstaPismenaNaziv, Smjer smjer, byte[] pdf) {
        EgopPismenoEntity e = new EgopPismenoEntity();
        e.id = UUID.randomUUID();
        e.submissionId = submissionId;
        e.rn = rn;
        e.actRef = actRef;
        e.vrstaPismenaNaziv = vrstaPismenaNaziv;
        e.smjer = smjer;
        e.pdfContent = pdf;
        e.status = EgopSyncStatus.NEW;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    /**
     * Registracijski akt: nastaje tek nakon {@code KreirajPismeno2}, jer se u tom toku
     * PDF renderira <i>iz</i> urudžbenog broja koji taj poziv vrati.
     */
    public static EgopPismenoEntity create(UUID submissionId, String vrstaPismenaNaziv,
                                           Smjer smjer, int jop, String urBroj,
                                           String egopVrstaSifra, boolean egopVrstaPrivremena) {
        EgopPismenoEntity e = new EgopPismenoEntity();
        e.id = UUID.randomUUID();
        e.submissionId = submissionId;
        e.actRef = ACT_REF_REGISTRACIJA;
        e.vrstaPismenaNaziv = vrstaPismenaNaziv;
        e.smjer = smjer;
        e.jop = jop;
        e.urBroj = urBroj;
        e.egopVrstaSifra = egopVrstaSifra;
        e.egopVrstaPrivremena = egopVrstaPrivremena;
        e.status = EgopSyncStatus.PISMENO_OK;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    /** Pismeno je kreirano u eGOP-u; dokument još nije priložen. */
    public void applyPismeno(int jop, String urBroj, String egopVrstaSifra,
                             boolean egopVrstaPrivremena) {
        this.jop = jop;
        this.urBroj = urBroj;
        this.egopVrstaSifra = egopVrstaSifra;
        this.egopVrstaPrivremena = egopVrstaPrivremena;
        this.status = EgopSyncStatus.PISMENO_OK;
        this.updatedAt = Instant.now();
    }

    /**
     * Prilaganje dokumenta je zadnji korak — pismeno postoji u eGOP-u i ima svoj dokument,
     * pa je time dovršeno. Status se postavlja ovdje, u istom upisu, da red ne ostane na
     * {@code PISMENO_OK} iako je posao gotov.
     */
    public void markDocumentAttached() {
        this.documentAttached = true;
        this.status = EgopSyncStatus.SYNCED;
        this.syncError = null;
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void markSynced() {
        this.status = EgopSyncStatus.SYNCED;
        this.syncError = null;
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error, Instant nextAttemptAt) {
        this.status = EgopSyncStatus.FAILED;
        this.syncError = error;
        this.syncAttempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = Instant.now();
    }

    public boolean isSynced() {
        return status == EgopSyncStatus.SYNCED;
    }

    /** Pismeno postoji u eGOP-u ako mu je dodijeljen {@code jop}. */
    public boolean isFiled() {
        return jop != null;
    }
}
