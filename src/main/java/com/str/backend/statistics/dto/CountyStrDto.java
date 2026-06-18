package com.str.backend.statistics.dto;

public record CountyStrDto(
        String countyId,
        String countyName,
        long accommodations,
        long activeRn,
        long suspendedRn,
        long withdrawnRn,
        double registrationRate
) {
}
