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
        String taxNumber,
        String countryName,
        LessorApplicationStatus applicationStatus,
        Instant createdAt,
        String documentType,
        String documentNumber
) {}
