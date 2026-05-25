package com.str.backend.email.event;

import java.util.UUID;

public record RegistrationRejectedEvent(
        UUID lessorId,
        String email,
        String firstName
) {
}
