package com.str.backend.email.event;

import java.util.UUID;

public record RegistrationApprovedEvent(
        UUID lessorId,
        String email,
        String firstName,
        String username
) {
}
