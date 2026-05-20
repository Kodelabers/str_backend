package com.str.backend.lessor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "lessor_document", schema = "str_rn")
public class LessorDocumentEntity {

    @Id
    @Column(name = "document_id", updatable = false, nullable = false)
    private UUID documentId;

    @Column(name = "lessor_id", updatable = false, nullable = false)
    private UUID lessorId;

    @Column(name = "document_type", updatable = false, nullable = false, length = 32)
    private String documentType;

    @Column(name = "document_number", updatable = false, nullable = false, length = 64)
    private String documentNumber;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "front_image", updatable = false, nullable = false)
    private byte[] frontImage;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "back_image", updatable = false)
    private byte[] backImage;

    @Column(name = "uploaded_at", updatable = false, nullable = false)
    private Instant uploadedAt;

    public static LessorDocumentEntity create(UUID lessorId, String documentType,
                                               String documentNumber,
                                               byte[] frontImage, byte[] backImage) {
        LessorDocumentEntity d = new LessorDocumentEntity();
        d.documentId = UUID.randomUUID();
        d.lessorId = lessorId;
        d.documentType = documentType;
        d.documentNumber = documentNumber;
        d.frontImage = frontImage;
        d.backImage = backImage;
        d.uploadedAt = Instant.now();
        return d;
    }
}
