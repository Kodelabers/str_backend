package com.str.backend.objection;

import com.str.backend.domain.RnStatus;
import com.str.backend.exception.IllegalStatusTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Prigovor iznajmljivača na prijedlog suspenzije ili na suspenziju RB-a — dokument (PDF ili
 * Word) koji iznajmljivač predaje, a službenik na detalju RB-a uvažava ili ne uvažava.
 *
 * <p>Vlasnik je, kao kod rješenja o kategorizaciji, NIAS iznajmljivač po OIB-u ili non-EU
 * iznajmljivač po {@code lessorId}; barem jedno od dvoje je zadano.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(schema = "str_rn", name = "suspension_objection")
public class SuspensionObjectionEntity {

    @Id
    @Column(name = "objection_id", updatable = false, nullable = false)
    private UUID objectionId;

    @Column(name = "rn", updatable = false, nullable = false, length = 20)
    private String rn;

    /** OIB vlasnika (NIAS predaja; OIB tvrtke kad osoba djeluje u njezino ime); {@code null} za non-EU. */
    @Column(name = "lessor_oib", updatable = false, length = 11)
    private String lessorOib;

    /** Non-EU iznajmljivač koji je predao prigovor; {@code null} za NIAS predaju. */
    @Column(name = "lessor_id", updatable = false)
    private UUID lessorId;

    /** Email non-EU iznajmljivača u trenutku predaje — snimka, ne prati kasnije promjene. */
    @Column(name = "lessor_email", updatable = false, length = 255)
    private String lessorEmail;

    /** Tko je fizički predao: {@code NIAS:<oib osobe>} ili {@code LESSOR:<lessorId>}. */
    @Column(name = "submitted_by", updatable = false, nullable = false, length = 128)
    private String submittedBy;

    /** Status RB-a pri predaji — prigovara li se prijedlogu suspenzije ili suspenziji. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rn_status", updatable = false, nullable = false, length = 32)
    private RnStatus rnStatus;

    /** Zapis prijelaza RB-a u {@link #rnStatus} — akt koji se prigovorom pobija. */
    @Column(name = "rn_log_id", updatable = false)
    private UUID rnLogId;

    @Column(name = "file_name", updatable = false, nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", updatable = false, nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", updatable = false, nullable = false)
    private long fileSize;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "file_content", updatable = false, nullable = false)
    private byte[] fileContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SuspensionObjectionStatus status;

    @Column(name = "submitted_at", updatable = false, nullable = false)
    private Instant submittedAt;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    /** Predaja NIAS iznajmljivača — vlasnik je OIB. */
    public static SuspensionObjectionEntity createForOib(String lessorOib, String rn, RnStatus rnStatus,
                                                         UUID rnLogId, String submittedBy, String fileName,
                                                         String contentType, byte[] fileContent) {
        SuspensionObjectionEntity e = create(rn, rnStatus, rnLogId, submittedBy, fileName, contentType, fileContent);
        e.lessorOib = Objects.requireNonNull(lessorOib, "lessorOib");
        return e;
    }

    /** Predaja non-EU iznajmljivača — vlasnik je {@code lessorId}, OIB-a nema. */
    public static SuspensionObjectionEntity createForLessor(UUID lessorId, String lessorEmail, String rn,
                                                            RnStatus rnStatus, UUID rnLogId, String submittedBy,
                                                            String fileName, String contentType,
                                                            byte[] fileContent) {
        SuspensionObjectionEntity e = create(rn, rnStatus, rnLogId, submittedBy, fileName, contentType, fileContent);
        e.lessorId = Objects.requireNonNull(lessorId, "lessorId");
        e.lessorEmail = lessorEmail;
        return e;
    }

    private static SuspensionObjectionEntity create(String rn, RnStatus rnStatus, UUID rnLogId, String submittedBy,
                                                    String fileName, String contentType, byte[] fileContent) {
        SuspensionObjectionEntity e = new SuspensionObjectionEntity();
        e.objectionId = UUID.randomUUID();
        e.rn = Objects.requireNonNull(rn, "rn");
        e.rnStatus = Objects.requireNonNull(rnStatus, "rnStatus");
        e.rnLogId = rnLogId;
        e.submittedBy = Objects.requireNonNull(submittedBy, "submittedBy");
        e.fileName = fileName;
        e.contentType = contentType;
        e.fileContent = fileContent;
        e.fileSize = fileContent.length;
        e.status = SuspensionObjectionStatus.SUBMITTED;
        e.submittedAt = Instant.now();
        return e;
    }

    /** Službenik uvažava prigovor. Status RB-a mijenja pozivatelj ({@code SuspensionObjectionAdminService}). */
    public void accept(String actor, String note) {
        resolve(SuspensionObjectionStatus.ACCEPTED, actor, note);
    }

    /** Službenik ne uvažava prigovor; RB ostaje gdje jest. */
    public void reject(String actor, String note) {
        resolve(SuspensionObjectionStatus.REJECTED, actor, note);
    }

    /** RB je napustio prijedlog suspenzije i suspenziju drugim putem — o prigovoru se više ne odlučuje. */
    public void close(String actor) {
        resolve(SuspensionObjectionStatus.CLOSED, actor, null);
    }

    /** Odlučuje se samo o neobrađenom (SUBMITTED) prigovoru — inače 409. */
    private void resolve(SuspensionObjectionStatus target, String actor, String note) {
        if (status != SuspensionObjectionStatus.SUBMITTED) {
            throw new IllegalStatusTransitionException(
                    "Prigovor " + objectionId + " nije u statusu SUBMITTED (trenutno: " + status + ")");
        }
        this.status = target;
        this.resolvedBy = actor;
        this.resolvedAt = Instant.now();
        this.resolutionNote = note;
    }
}
