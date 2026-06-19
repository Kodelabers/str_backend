package com.str.backend.statistics.dto;

public record CountryBreakdownDto(
        String country,
        long nights,
        long guests,
        double sharePercent
) {}
