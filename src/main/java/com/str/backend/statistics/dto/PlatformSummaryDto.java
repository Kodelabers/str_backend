package com.str.backend.statistics.dto;

public record PlatformSummaryDto(
        long accommodationsWithActivities,
        long platformsReporting,
        long totalNights,
        long totalGuests,
        long anomalies
) {}
