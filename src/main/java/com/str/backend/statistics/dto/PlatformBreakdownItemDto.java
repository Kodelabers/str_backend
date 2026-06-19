package com.str.backend.statistics.dto;

import java.util.List;

public record PlatformBreakdownItemDto(
        String platformId,
        String platformName,
        long nights,
        long guests,
        double sharePercent,
        List<CountryBreakdownDto> countries
) {}
