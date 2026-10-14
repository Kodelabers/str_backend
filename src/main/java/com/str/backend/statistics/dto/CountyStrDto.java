package com.str.backend.statistics.dto;

public record CountyStrDto(
        String countyId,
        String countyName,
        long accommodations,
        long activeRn,
        long suspendedRn,
        /** Opozvani — iznajmljivač je sam opozvao RB. */
        long revokedRn,
        /** Povučeni — nadležno tijelo je RB povuklo po službenoj dužnosti. */
        long withdrawnRn,
        double registrationRate
) {
}
