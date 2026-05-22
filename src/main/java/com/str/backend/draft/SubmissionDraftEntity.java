package com.str.backend.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "submission_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionDraftEntity {

    @Id
    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, updatable = false, length = 16)
    private DraftOwnerType ownerType;

    @Column(name = "owner_key", nullable = false, updatable = false, length = 64)
    private String ownerKey;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SubmissionDraftEntity create(DraftOwner owner, String title, byte[] encryptedPayload) {
        SubmissionDraftEntity e = new SubmissionDraftEntity();
        e.draftId = UUID.randomUUID();
        e.ownerType = owner.type();
        e.ownerKey = owner.key();
        e.title = title;
        e.payload = encryptedPayload;
        return e;
    }

    public void update(String title, byte[] encryptedPayload) {
        this.title = title;
        this.payload = encryptedPayload;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
