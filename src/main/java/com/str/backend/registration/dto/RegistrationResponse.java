package com.str.backend.registration.dto;

import java.util.UUID;

/**
 * Odgovor na izdavanje RB-a.
 *
 * @param rjesenjePreneseno ponovni zahtjev nakon povlačenja (T15): {@code true} samo kad je rješenje o
 *                          kategorizaciji povučenog RB-a stvarno preneseno na novi RB (isti objekt).
 *                          Kad je {@code false}, novi objekt traži rješenje kao i svaki drugi novi
 *                          objekt (upload nakon izdavanja).
 */
public record RegistrationResponse(String registrationNumber, UUID submissionId, boolean rjesenjePreneseno) {

    public RegistrationResponse(String registrationNumber, UUID submissionId) {
        this(registrationNumber, submissionId, false);
    }
}
