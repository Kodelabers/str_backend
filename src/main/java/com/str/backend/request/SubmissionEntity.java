package com.str.backend.request;

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

    @Column(name = "filing_number", length = 64, nullable = false, updatable = false)
    private String filingNumber;

    @Column(name = "document_link", length = 500)
    @Setter private String documentLink;

@Column(name = "lessor_id", nullable = false, updatable = false)
    private UUID lessorId;

    @Column(name = "authority_id")
    private Long competentAuthorityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private SubmissionStatus status;

    @Column(name = "filing_date")
    private Instant filingDate;

    @JdbcTypeCode(SqlTypes.BLOB)
    @Column(name = "pdf_content")
    @Setter private byte[] pdfContent;

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
}
