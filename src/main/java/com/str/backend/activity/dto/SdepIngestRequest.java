package com.str.backend.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class SdepIngestRequest {

    @NotNull
    private Long platformId;

    @NotEmpty
    @Valid
    private List<Entry> entries;

    /**
     * Jedna aktivnost. Polja od {@code sdepActivityId} nadalje šalje SDEP (dogovoreno 3.11.2026.,
     * T6/T7/T9). Unos bez {@code sdepActivityId} (ručni ingest, stari SDEP) uvijek se dodaje kao novi
     * redak, kao i dosad.
     */
    @Data
    public static class Entry {

        /** Kako ga je prijavila platforma; ne mora biti naš broj (T7), valjanost se označava. */
        @NotBlank
        @Size(max = 128)
        private String rn;

        private UUID accommodationId;

        @NotNull
        private LocalDate periodFrom;

        @NotNull
        private LocalDate periodTo;

        private int numberOfNights;
        private int numberOfGuests;

        /** Države bez ponavljanja; ostaje dok se sve ne čita iz {@link #guests}. */
        @Size(max = 4096)
        private String guestCountries;

        /** ID aktivnosti u SDEP-u: s platformom jedinstven, ključ za ispravke (T6). */
        @Size(max = 64)
        private String sdepActivityId;

        /** Vrijeme verzije u SDEP-u; novija zamjenjuje stariju. Obavezno uz {@code sdepActivityId}. */
        private OffsetDateTime sdepVersion;

        /** {@code finished} ili {@code cancelled}; otkazana ostaje zapisana, ali izvan statistike. */
        @Pattern(regexp = "finished|cancelled")
        private String status;

        /** Broj gostiju po državi (ISO 3166-1 alpha-3 ili {@code N/A}), za tablicu {@code guest}. */
        @Valid
        @Size(max = 1024)
        private List<Guest> guests;

        @Size(max = 2048)
        private String url;

        @Size(max = 64)
        private String activityName;

        @Size(max = 328)
        private String fullAddress;

        public boolean isCancelled() {
            return "cancelled".equals(status);
        }
    }

    @Data
    public static class Guest {

        @NotNull
        @Pattern(regexp = "[A-Z]{3}|N/A")
        private String country;

        @Min(1)
        private int count;
    }
}
