package com.str.backend.accommodation;

import com.str.backend.domain.OfferType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "accommodation_unit")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AccommodationUnitEntity {

    @Id
    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID accommodationUnitId;

    @Column(name = "accommodation_id", nullable = false, updatable = false)
    private UUID accommodationId;

    @Column(name = "unit_type", length = 64, nullable = false)
    private String unitType;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "bed_count", nullable = false)
    private int numberOfBeds;

    @Column(name = "unit_count", nullable = false)
    private int numberOfIdentical;

    @Column(name = "floor", length = 8)
    private String floor;

    @Column(name = "category", length = 32)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "offer_type", length = 16)
    private OfferType offerType;

    @Column(name = "lessor_residence")
    private Boolean lessorResidence;

    @Column(name = "co_owner_consent")
    private Boolean coOwnerConsent;

    @Column(name = "consent_date")
    private LocalDate consentDate;

    @Column(name = "consent_withdrawal_date")
    private LocalDate consentWithdrawalDate;

    @Column(name = "rn", length = 18)
    private String rn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static AccommodationUnitEntity create(UUID accommodationId, String unitType, int numberOfBeds,
                                                 int numberOfIdentical) {
        AccommodationUnitEntity e = new AccommodationUnitEntity();
        e.accommodationUnitId = UUID.randomUUID();
        e.accommodationId = accommodationId;
        e.unitType = unitType;
        e.numberOfBeds = numberOfBeds;
        e.numberOfIdentical = numberOfIdentical;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void setDetails(String code, String floor, String category, OfferType offerType,
                           Boolean lessorResidence) {
        this.code = code;
        this.floor = floor;
        this.category = category;
        this.offerType = offerType;
        this.lessorResidence = lessorResidence;
        this.updatedAt = Instant.now();
    }

    public void setConsent(Boolean coOwnerConsent, LocalDate consentDate,
                           LocalDate consentWithdrawalDate) {
        this.coOwnerConsent = coOwnerConsent;
        this.consentDate = consentDate;
        this.consentWithdrawalDate = consentWithdrawalDate;
        this.updatedAt = Instant.now();
    }

    public void assignRn(String rn) {
        this.rn = rn;
        this.updatedAt = Instant.now();
    }
}
