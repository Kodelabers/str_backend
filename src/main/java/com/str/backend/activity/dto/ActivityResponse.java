package com.str.backend.activity.dto;

import com.str.backend.activity.RnCheck;
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
    /** Valjanost prijavljenog broja pri primitku (T7). */
    private RnCheck rnCheck;
    private String url;
    private String activityName;
    private String fullAddress;
}
