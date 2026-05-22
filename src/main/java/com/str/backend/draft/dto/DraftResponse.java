package com.str.backend.draft.dto;

import com.str.backend.draft.DraftOwnerType;

import java.time.Instant;
import java.util.UUID;

public record DraftResponse(
        UUID draftId,
        String title,
        DraftOwnerType ownerType,
        String payload,
        Instant createdAt,
        Instant updatedAt
) {
}
