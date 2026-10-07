package com.str.backend.email.event;

import java.util.UUID;

/**
 * Non-EU iznajmljivač se upravo registrirao. Nosi lozinku u čitljivom obliku jer je poruka
 * dobrodošlice navodi; zato event živi samo u memoriji i ne smije se nigdje spremati (ni u red
 * ponovnog slanja) ni logirati — {@link #toString()} je maskira.
 */
public record RegistrationSubmittedEvent(
        UUID lessorId,
        String email,
        String firstName,
        String username,
        String password
) {
    @Override
    public String toString() {
        return "RegistrationSubmittedEvent[lessorId=" + lessorId + ", email=" + email
                + ", firstName=" + firstName + ", username=" + username + ", password=***]";
    }
}
