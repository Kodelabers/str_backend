package com.str.backend.prefill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "registration_number_prefill")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationNumberPrefillEntity {

    @Id
    @Column(name = "prefill_id", nullable = false, updatable = false)
    private UUID prefillId;

    @Column(name = "oib", nullable = false, updatable = false, length = 11)
    private String oib;

    @Column(name = "first_name", nullable = false, updatable = false, length = 128)
    private String firstName;

    @Column(name = "last_name", nullable = false, updatable = false, length = 128)
    private String lastName;

    @Column(name = "address_code", updatable = false, length = 64)
    private String addressCode;

    @Column(name = "max_bed_count", updatable = false)
    private Integer maxBedCount;

    @Column(name = "max_guest_count", updatable = false)
    private Integer maxGuestCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static RegistrationNumberPrefillEntity create(String oib,
                                                         String firstName,
                                                         String lastName,
                                                         String addressCode,
                                                         Integer maxBedCount,
                                                         Integer maxGuestCount) {
        RegistrationNumberPrefillEntity e = new RegistrationNumberPrefillEntity();
        e.prefillId = UUID.randomUUID();
        e.oib = oib;
        e.firstName = firstName;
        e.lastName = lastName;
        e.addressCode = addressCode;
        e.maxBedCount = maxBedCount;
        e.maxGuestCount = maxGuestCount;
        return e;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
