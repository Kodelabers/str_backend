package com.str.backend.statistics.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PlatformActivityRowDto(
        String id,
        String rb,
        String ownerName,
        String address,
        String city,
        String countyId,
        String countyName,
        List<PlatformChipDto> platforms,
        LocalDate periodFrom,
        LocalDate periodTo,
        long nights,
        long guestsTotal,
        double avgGuestsPerNight,
        String rnStatus,
        Instant reportedAt
) {}
