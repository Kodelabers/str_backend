package com.str.backend.guest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "guest")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class GuestEntity {

    @Id
    @Column(name = "guest_id", nullable = false, updatable = false)
    private UUID guestId;

    @Column(name = "activity_id", nullable = false, updatable = false)
    private UUID activityId;

    @Column(name = "guest_count", nullable = false)
    private int guestCount;

    @Column(name = "country", length = 64, nullable = false)
    private String country;

    public static GuestEntity create(UUID activityId, int guestCount, String country) {
        GuestEntity g = new GuestEntity();
        g.guestId = UUID.randomUUID();
        g.activityId = activityId;
        g.guestCount = guestCount;
        g.country = country;
        return g;
    }
}
