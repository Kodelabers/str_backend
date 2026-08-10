package com.str.backend.categorization;

import java.time.Instant;
import java.util.UUID;

/** Odgovor na upload — frontend iz njega odmah složi novi red u tablici objekata. */
public record CategorizationDecisionResponse(UUID decisionId,
                                             CategorizationDecisionStatus status,
                                             String fileName,
                                             long fileSize,
                                             Instant uploadedAt) {

    public static CategorizationDecisionResponse of(CategorizationDecisionEntity e) {
        return new CategorizationDecisionResponse(e.getDecisionId(), e.getStatus(),
                e.getFileName(), e.getFileSize(), e.getUploadedAt());
    }
}
