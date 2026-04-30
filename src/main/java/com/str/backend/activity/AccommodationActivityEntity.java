package com.str.backend.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "rn", length = 18, nullable = false)
    private String rn;

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

    @Column(name = "guest_country", length = 64)
    private String guestCountries;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "purge_after", nullable = false, updatable = false)
    private Instant purgeAfter;

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
}
