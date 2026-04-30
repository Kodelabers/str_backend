package com.str.backend.activity.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class ActivityResponse {

    private UUID activityId;
    private Long platformId;
    private String rn;
    private UUID accommodationId;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private int numberOfNights;
    private int numberOfGuests;
    private String guestCountries;
    private Instant receivedAt;
}
