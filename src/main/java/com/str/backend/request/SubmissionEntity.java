package com.str.backend.request;

import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.domain.SubmissionChannel;
import com.str.backend.domain.SubmissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "submission")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SubmissionEntity {

    @Id
    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Column(name = "filing_number", length = 64)
    @Setter private String filingNumber;

    @Column(name = "document_link", length = 500)
    @Setter private String documentLink;

    @Column(name = "lessor_id", nullable = false, updatable = false)
    private UUID lessorId;

    @Column(name = "authority_id")
    private Long competentAuthorityId;

    @Column(name = "submission_type_id")
    @Setter private Long submissionTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 16, nullable = false)
    private SubmissionChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private SubmissionStatus status;

    @Column(name = "filing_date")
    @Setter private Instant filingDate;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "pdf_content", length = 10_485_760)
    @Setter private byte[] pdfContent;

    @Column(name = "egop_uredska_godina")
    private Integer egopUredskaGodina;

    @Column(name = "egop_rbr_predmeta")
    private Integer egopRbrPredmeta;

    @Column(name = "egop_klasa", length = 64)
    private String egopKlasa;

    @Enumerated(EnumType.STRING)
    @Column(name = "egop_sync_status", length = 20, nullable = false)
    private EgopSyncStatus egopSyncStatus = EgopSyncStatus.NEW;

    @Column(name = "egop_sync_error")
    private String egopSyncError;

    @Column(name = "egop_sync_attempts", nullable = false)
    private int egopSyncAttempts = 0;

    /** Vrijeme sljedećeg dopuštenog eGOP pokušaja (eksponencijalni backoff); {@code null} = odmah. */
    @Column(name = "egop_next_attempt_at")
    private Instant egopNextAttemptAt;

    /** Postavlja se tek nakon uspješno poslanog maila o dodjeli RN-a (non-EU dostava),
     *  pa ponovljeni dispatch/retry ne šalje duplikate. */
    @Column(name = "rn_email_sent_at")
    private Instant rnEmailSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SubmissionEntity create(String filingNumber, UUID lessorId,
                                          Long competentAuthorityId, Instant filingDate,
                                          String documentLink, byte[] pdfContent) {
        SubmissionEntity z = new SubmissionEntity();
        z.submissionId = UUID.randomUUID();
        z.filingNumber = filingNumber;
        z.lessorId = lessorId;
        z.competentAuthorityId = competentAuthorityId;
        z.filingDate = filingDate;
        z.documentLink = documentLink;
        z.pdfContent = pdfContent;
        z.channel = SubmissionChannel.NIAS;
        z.status = SubmissionStatus.IN_PROCESSING;
        Instant now = Instant.now();
        z.createdAt = now;
        z.updatedAt = now;
        return z;
    }

    void applyStatus(SubmissionStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    /**
     * Identitet eGOP predmeta u koji se urudžbiraju svi akti ovog submissiona
     * (i budući akti životnog ciklusa RB-a — zato se sprema trajno).
     */
    public void applyEgopPredmet(int uredskaGodina, int rbrPredmeta, String klasa) {
        this.egopUredskaGodina = uredskaGodina;
        this.egopRbrPredmeta = rbrPredmeta;
        this.egopKlasa = klasa;
        this.updatedAt = Instant.now();
    }

    public void applyEgopSyncStatus(EgopSyncStatus next) {
        this.egopSyncStatus = next;
        if (next != EgopSyncStatus.FAILED) {
            this.egopSyncError = null;
        }
        if (next == EgopSyncStatus.SYNCED) {
            this.egopNextAttemptAt = null;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * @param nextAttemptAt kad retry job smije pokušati ponovo; {@code null} znači bez
     *                      odgode (npr. kad su pokušaji iscrpljeni pa ionako nije kandidat)
     */
    public void markEgopFailed(String error, Instant nextAttemptAt) {
        this.egopSyncStatus = EgopSyncStatus.FAILED;
        this.egopSyncError = error;
        this.egopSyncAttempts++;
        this.egopNextAttemptAt = nextAttemptAt;
        this.updatedAt = Instant.now();
    }

    /** Dostava RN-a mailom je obavljena — sljedeći dispatch/retry je preskače. */
    public void markRnEmailSent() {
        this.rnEmailSentAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
