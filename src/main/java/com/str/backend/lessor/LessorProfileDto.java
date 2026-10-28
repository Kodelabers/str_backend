package com.str.backend.lessor;

import com.str.backend.domain.LessorApplicationStatus;

import java.time.Instant;
import java.util.UUID;

public record LessorProfileDto(
        UUID lessorId,
        String firstName,
        String lastName,
        String email,
        String mobileNumber,
        /**
         * Telefon i kontakt osoba koje je non-EU iznajmljivač zadnji upisao uz zahtjev za RB —
         * obrazac njima predpopunjava kontakt. NIAS profil ({@code /api/nias/profile}) ih ne vraća.
         */
        String phoneNumber,
        String contactName,
        String taxNumber,
        String countryName,
        LessorApplicationStatus applicationStatus,
        Instant createdAt,
        String documentType,
        String documentNumber
) {}
