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
        String representativeAddress
) {}
