package com.str.backend.statistics.dto;

import java.util.List;

public record PlatformBreakdownDto(
        String rn,
        long totalNights,
        long totalGuests,
        List<PlatformBreakdownItemDto> platforms
) {}
