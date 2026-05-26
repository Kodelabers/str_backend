package com.str.backend.admin.dto;

import com.str.backend.domain.LessorApplicationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PendingRegistrationDetailDto(
        UUID lessorId,
        String firstName,
        String lastName,
        String email,
        LocalDate dateOfBirth,
        Integer countryOfResidenceId,
        String countryOfResidenceName,
        String taxNumber,
        String street,
        String mobileNumber,
        LessorApplicationStatus applicationStatus,
        Instant createdAt,
        DocumentMetaDto document
) {}
