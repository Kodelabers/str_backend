package com.str.backend.lessor;

import java.util.UUID;

public record LessorRegistrationResponse(
        UUID lessorId,
        String username
) {}
