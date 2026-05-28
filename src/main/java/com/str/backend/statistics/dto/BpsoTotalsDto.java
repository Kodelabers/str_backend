package com.str.backend.statistics.dto;

public record BpsoTotalsDto(
        long totalObjects,
        long totalRn,
        double coverageRate
) {
}
