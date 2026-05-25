package com.str.backend.admin.dto;

public record PendingRegistrationStatsDto(
        long totalPending,
        long receivedToday,
        long olderThan5Days
) {}
