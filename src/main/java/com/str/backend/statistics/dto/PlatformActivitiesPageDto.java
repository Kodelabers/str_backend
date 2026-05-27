package com.str.backend.statistics.dto;

import java.util.List;

public record PlatformActivitiesPageDto(
        List<PlatformActivityRowDto> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {}
