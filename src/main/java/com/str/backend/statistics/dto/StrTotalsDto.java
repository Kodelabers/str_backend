package com.str.backend.statistics.dto;

public record StrTotalsDto(
        long totalObjects,
        long totalRn,
        double coverageRate
) {
}
