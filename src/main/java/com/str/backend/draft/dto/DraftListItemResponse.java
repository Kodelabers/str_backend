package com.str.backend.draft.dto;

import com.str.backend.draft.DraftOwnerType;

import java.time.Instant;
import java.util.UUID;

public record DraftListItemResponse(
        UUID draftId,
        String title,
        DraftOwnerType ownerType,
        Instant createdAt,
        Instant updatedAt
) {
}
