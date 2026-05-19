package com.str.backend.auth.dto;

import java.util.UUID;

public record MeResponse(
        UUID lessorId,
        String username,
        String firstName,
        String lastName,
        String email
) {}
