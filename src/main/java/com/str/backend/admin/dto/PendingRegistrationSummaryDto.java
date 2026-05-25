package com.str.backend.admin.dto;

import com.str.backend.domain.SubmissionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PendingRegistrationSummaryDto(
        UUID lessorId,
        String firstName,
        String lastName,
        String email,
        LocalDate dateOfBirth,
        Integer countryOfResidenceId,
        String countryOfResidenceName,
        String taxNumber,
        SubmissionStatus applicationStatus,
        Instant createdAt,
        String documentType,
        String documentNumber
) {}
