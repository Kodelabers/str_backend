package com.str.backend.email.event;

import java.util.UUID;

/**
 * @param username prijava iznajmljivača — po njoj se gase njegove otvorene sesije, jer se
 *                 non-EU iznajmljivač prijavljuje već dok zahtjev čeka pregled
 */
public record RegistrationRejectedEvent(
        UUID lessorId,
        String email,
        String firstName,
        String username
) {
}
