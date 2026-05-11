package com.str.backend.registration.dto;

import java.util.List;
import java.util.UUID;

public record RegistrationResponse(
        UUID lessorId,
        UUID submissionId,
        List<AssignedRb> assignedRbs
) {
    public record AssignedRb(UUID accommodationId, String rn) {}
}
