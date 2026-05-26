package com.str.backend.statistics.dto;

public record BpsoTotalsDto(
        long accommodations,
        long activeRn,
        long suspendedRn,
        long withdrawnRn,
        double registrationRate
) {
}
