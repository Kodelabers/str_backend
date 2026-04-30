package com.str.backend.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "submission_attachment")
public class SubmissionAttachmentEntity {

    @Id
    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Column(name = "attachment_type", length = 64, nullable = false)
    private String attachmentType;

    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "uri", length = 1024, nullable = false)
    private String uri;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected SubmissionAttachmentEntity() {
    }

    public static SubmissionAttachmentEntity upload(UUID submissionId, String attachmentType, String fileName,
                                                    String mimeType, Long sizeBytes, String uri, String sha256) {
        SubmissionAttachmentEntity e = new SubmissionAttachmentEntity();
        e.attachmentId = UUID.randomUUID();
        e.submissionId = submissionId;
        e.attachmentType = attachmentType;
        e.fileName = fileName;
        e.mimeType = mimeType;
        e.sizeBytes = sizeBytes;
        e.uri = uri;
        e.sha256 = sha256;
        e.uploadedAt = Instant.now();
        return e;
    }

    public UUID getAttachmentId() { return attachmentId; }
    public UUID getSubmissionId() { return submissionId; }
    public String getAttachmentType() { return attachmentType; }
    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getUri() { return uri; }
    public String getSha256() { return sha256; }
    public Instant getUploadedAt() { return uploadedAt; }
}
