package com.str.backend.rn.dto;

import com.str.backend.domain.RnStatus;

import java.time.LocalDate;
import java.util.UUID;

public record RnSummaryDto(
        String rn,
        RnStatus status,
        LocalDate issueDate,
        LocalDate validFrom,
        LocalDate validTo,
        /** Rok za očitovanje; popunjen samo u statusu SUSPENSION_PROPOSED. */
        LocalDate suspensionDeadline,
        UUID accommodationId,
        String county,
        String city,
        String street,
        String streetNumber,
        String accommodationName,
        String accommodationTypeName,
        UUID lessorId,
        String lessorFirstName,
        String lessorLastName,
        String lessorLegalEntityName
) {}
