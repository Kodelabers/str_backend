package com.str.backend.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "accommodation_activity")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AccommodationActivityEntity {

    public static final int RETENTION_MONTHS = 18;

    @Id
    @Column(name = "activity_id", nullable = false, updatable = false)
    private UUID activityId;

    @Column(name = "platform_id", nullable = false)
    private Long platformId;

    /** Kako ga je platforma prijavila, do 128 znakova (EU model); valjanost je u {@link #rnCheck}. */
    @Column(name = "rn", length = 128, nullable = false)
    private String rn;

    @Enumerated(EnumType.STRING)
    @Column(name = "rn_check", length = 16, nullable = false)
    private RnCheck rnCheck = RnCheck.VALID;

    @Column(name = "accommodation_id")
    private UUID accommodationId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "overnight_stays", nullable = false)
    private int numberOfNights;

    @Column(name = "guest_count", nullable = false)
    private int numberOfGuests;

    @Column(name = "guest_country", length = 4096)
    private String guestCountries;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "purge_after", nullable = false, updatable = false)
    private Instant purgeAfter;

    /** ID aktivnosti u SDEP-u; s {@code platformId} jedinstven (T6). NULL za unose bez SDEP-a. */
    @Column(name = "sdep_activity_id", length = 64, updatable = false)
    private String sdepActivityId;

    /** Vrijeme verzije u SDEP-u: novija verzija zamjenjuje redak, ista ili starija ne mijenja ništa. */
    @Column(name = "sdep_version")
    private Instant sdepVersion;

    /** Otkazana aktivnost ostaje zapisana, ali je izvan pregleda i statistike. */
    @Column(name = "cancelled", nullable = false)
    private boolean cancelled;

    @Column(name = "url", length = 2048)
    private String url;

    @Column(name = "activity_name", length = 64)
    private String activityName;

    @Column(name = "full_address", length = 328)
    private String fullAddress;

    public static AccommodationActivityEntity ingest(Long platformId, String rn, UUID accommodationId,
                                                     LocalDate od, LocalDate toDate,
                                                     int numberOfNights, int numberOfGuests,
                                                     String guestCountries) {
        AccommodationActivityEntity e = new AccommodationActivityEntity();
        e.activityId = UUID.randomUUID();
        e.platformId = platformId;
        e.rn = rn;
        e.accommodationId = accommodationId;
        e.periodFrom = od;
        e.periodTo = toDate;
        e.numberOfNights = numberOfNights;
        e.numberOfGuests = numberOfGuests;
        e.guestCountries = guestCountries;
        Instant now = Instant.now();
        e.receivedAt = now;
        e.purgeAfter = now.plus(RETENTION_MONTHS * 30L, ChronoUnit.DAYS);
        return e;
    }

    /** Valjanost broja pri primitku (T7); {@code null} ostavlja zatečenu oznaku. */
    public void markRnCheck(RnCheck check) {
        if (check != null) {
            rnCheck = check;
        }
    }

    /** Prva verzija aktivnosti iz SDEP-a (T6, T9). */
    public static AccommodationActivityEntity fromSdep(Long platformId, String sdepActivityId, Instant sdepVersion,
                                                       ListingData data, RnCheck rnCheck) {
        AccommodationActivityEntity e = ingest(platformId, data.rn(), null, data.periodFrom(), data.periodTo(),
                data.numberOfNights(), data.numberOfGuests(), data.guestCountries());
        e.sdepActivityId = sdepActivityId;
        e.applyVersion(sdepVersion, data, rnCheck);
        return e;
    }

    /**
     * Novija verzija iste aktivnosti zamjenjuje podatke retka (T6). Vrijeme primitka i rok brisanja
     * ostaju od prve verzije, jer se 18 mjeseci računa od prvog primitka.
     *
     * @return {@code false} ako verzija nije novija od zapisane, pa se ništa ne mijenja
     */
    public boolean applyCorrection(Instant newVersion, ListingData data, RnCheck rnCheck) {
        if (sdepVersion != null && newVersion != null && !newVersion.isAfter(sdepVersion)) {
            return false;
        }
        rn = data.rn();
        periodFrom = data.periodFrom();
        periodTo = data.periodTo();
        numberOfNights = data.numberOfNights();
        numberOfGuests = data.numberOfGuests();
        guestCountries = data.guestCountries();
        applyVersion(newVersion, data, rnCheck);
        return true;
    }

    private void applyVersion(Instant version, ListingData data, RnCheck check) {
        sdepVersion = version;
        cancelled = data.cancelled();
        markRnCheck(check);
        url = data.url();
        activityName = data.activityName();
        fullAddress = data.fullAddress();
    }

    /** Podaci jedne verzije aktivnosti iz SDEP-a. */
    public record ListingData(String rn, LocalDate periodFrom, LocalDate periodTo, int numberOfNights,
                              int numberOfGuests, String guestCountries, boolean cancelled, String url,
                              String activityName, String fullAddress) {
    }
}