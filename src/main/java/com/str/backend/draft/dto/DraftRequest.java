package com.str.backend.draft.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DraftRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull String payload
) {
}
