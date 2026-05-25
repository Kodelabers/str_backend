package com.str.backend.admin.dto;

import java.util.UUID;

public record DocumentMetaDto(
        UUID documentId,
        String documentType,
        String documentNumber,
        boolean hasFront,
        boolean hasBack
) {}
