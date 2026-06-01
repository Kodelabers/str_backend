package com.str.backend.lessor;

import com.str.backend.domain.RnStatus;

import java.time.LocalDate;

public record LessorRnSummaryDto(
        String rn,
        RnStatus status,
        LocalDate issueDate,
        String accommodationName,
        String street,
        String streetNumber,
        String city,
        String accommodationTypeName
) {}
