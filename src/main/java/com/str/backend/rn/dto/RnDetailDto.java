package com.str.backend.rn.dto;

import com.str.backend.domain.RnStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RnDetailDto(
        String rn,
        RnStatus status,
        LocalDate issueDate,
        LocalDate validFrom,
        LocalDate validTo,
        LocalDate suspensionDeadline,
        Instant createdAt,
        Instant updatedAt,
        UUID submissionId,

        UUID accommodationId,
        String county,
        String city,
        String settlement,
        String street,
        String streetNumber,
        String accommodationName,
        String accommodationTypeName,
        Integer maxBeds,
        String category,

        UUID lessorId,
        String lessorFirstName,
        String lessorLastName,
        String lessorLegalEntityName,
        String lessorEmail,
        String lessorOib,

        Boolean legalEntityOwner,
        String legalEntityCountryName,
        String legalEntityCity,
        String legalEntityRegistrationNumber,

        String representativeOib,
        String legalRepresentativeName,
        String representativeEmail,
        String representativePhone,
        String representativeAddress,

        /** Rok za očitovanje na prijedlog povlačenja; popunjen samo u statusu WITHDRAWAL_PROPOSED (T12). */
        LocalDate withdrawalDeadline,
        /**
         * Prijedlog povlačenja pokrenut iz suspenzije (T12): broj javno ostaje suspendiran. {@code true}
         * samo u statusu WITHDRAWAL_PROPOSED.
         */
        boolean prijedlogIzSuspenzije
) {

    /** Bez podataka o prijedlogu povlačenja — za RB izvan tog postupka (testovi, pregledi). */
    public RnDetailDto(String rn, RnStatus status, LocalDate issueDate, LocalDate validFrom, LocalDate validTo,
                       LocalDate suspensionDeadline, Instant createdAt, Instant updatedAt, UUID submissionId,
                       UUID accommodationId, String county, String city, String settlement, String street,
                       String streetNumber, String accommodationName, String accommodationTypeName,
                       Integer maxBeds, String category, UUID lessorId, String lessorFirstName,
                       String lessorLastName, String lessorLegalEntityName, String lessorEmail, String lessorOib,
                       Boolean legalEntityOwner, String legalEntityCountryName, String legalEntityCity,
                       String legalEntityRegistrationNumber, String representativeOib,
                       String legalRepresentativeName, String representativeEmail, String representativePhone,
                       String representativeAddress) {
        this(rn, status, issueDate, validFrom, validTo, suspensionDeadline, createdAt, updatedAt, submissionId,
                accommodationId, county, city, settlement, street, streetNumber, accommodationName,
                accommodationTypeName, maxBeds, category, lessorId, lessorFirstName, lessorLastName,
                lessorLegalEntityName, lessorEmail, lessorOib, legalEntityOwner, legalEntityCountryName,
                legalEntityCity, legalEntityRegistrationNumber, representativeOib, legalRepresentativeName,
                representativeEmail, representativePhone, representativeAddress, null, false);
    }
}
