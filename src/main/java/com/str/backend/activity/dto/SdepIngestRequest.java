package com.str.backend.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class SdepIngestRequest {

    @NotNull
    private Long platformId;

    @NotEmpty
    @Valid
    private List<Entry> entries;

    @Data
    public static class Entry {

        @NotNull
        private String rn;

        private UUID accommodationId;

        @NotNull
        private LocalDate periodFrom;

        @NotNull
        private LocalDate periodTo;

        private int numberOfNights;
        private int numberOfGuests;
        private String guestCountries;
    }
}
