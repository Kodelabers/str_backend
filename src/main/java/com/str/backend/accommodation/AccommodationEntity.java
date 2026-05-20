package com.str.backend.accommodation;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "accommodation")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AccommodationEntity {

    @Id
    @Column(name = "accommodation_id", nullable = false, updatable = false)
    private UUID accommodationId;

    @Column(name = "submission_id", updatable = false)
    private UUID submissionId;

    @Column(name = "accommodation_type_id")
    @Setter private Long accommodationTypeId;

    @Column(name = "core_object_id")
    @Setter private UUID coreObjectId;

    @Column(name = "accommodation_code", length = 64)
    @Setter private String accommodationCode;

    @Column(name = "county", length = 128, nullable = false, updatable = false)
    private String county;

    @Column(name = "city", length = 128, nullable = false, updatable = false)
    private String city;

    @Column(name = "settlement", length = 128)
    @Setter private String settlement;

    @Column(name = "street", length = 128, nullable = false, updatable = false)
    private String street;

    @Column(name = "street_number", length = 16, nullable = false, updatable = false)
    private String streetNumber;

    @Column(name = "cadastral_municipality", length = 128)
    @Setter private String cadastralMunicipality;

    @Column(name = "cadastral_parcel_number", length = 64)
    @Setter private String cadastralParcelNumber;

    @Column(name = "max_beds", nullable = false, updatable = false)
    private int maxBeds;

    @Column(name = "max_guests", nullable = false, updatable = false)
    private int maxGuests;

    @Enumerated(EnumType.STRING)
    @Column(name = "offer_type", length = 16, nullable = false, updatable = false)
    private OfferType offerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "offering", length = 8, nullable = false)
    @Setter private Offering offering;

    /** User-claimed flag from the registration form: is this property the lessor's primary/secondary residence. */
    @Column(name = "lessor_residence")
    @Setter private Boolean lessorResidence;

    /** MPGI-verified flag set by GO-1: does the lessor's official domicile match the property county. */
    @Column(name = "lessor_domicile")
    private Boolean lessorDomicile;

    @Column(name = "name", length = 255)
    @Setter private String name;

    @Column(name = "group_name", length = 128)
    @Setter private String group;

    @Column(name = "requested_category", length = 32)
    @Setter private String requestedCategory;

    @Column(name = "category", length = 32)
    @Setter private String category;

    @Column(name = "description", length = 1024)
    @Setter private String description;

    @Column(name = "brko", length = 64)
    @Setter private String brko;

    @Column(name = "note", length = 1024)
    @Setter private String note;

    @Column(name = "auxiliary_beds")
    @Setter private Integer auxiliaryBeds;

    @Column(name = "building", nullable = false, updatable = false)
    private boolean building;

    @Column(name = "floor", length = 8)
    @Setter private String floor;

    @Column(name = "apartments", nullable = false, updatable = false)
    private boolean apartments;

    @Column(name = "legalized", nullable = false, updatable = false)
    private boolean legalized;

    @Column(name = "old_building")
    @Setter private Boolean oldBuilding;

    @Column(name = "tourist_tax_exempt")
    @Setter private Boolean touristTaxExempt;

    @Column(name = "co_owner_consent")
    @Setter private Boolean coOwnerConsent;

    @Column(name = "consent_date")
    @Setter private LocalDate consentDate;

    @Column(name = "consent_withdrawal_date")
    @Setter private LocalDate consentWithdrawalDate;

    @Column(name = "host")
    private Boolean host;

    @Column(name = "host_date")
    private Instant hostDate;

    @Column(name = "host_withdrawal_date")
    private Instant hostWithdrawalDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static AccommodationEntity create(UUID submissionId, String county, String city, String street,
                                             String streetNumber, int maxBeds, int maxGuests, OfferType offerType,
                                             Offering offering, boolean building, boolean apartments,
                                             boolean legalized) {
        AccommodationEntity s = new AccommodationEntity();
        s.accommodationId = UUID.randomUUID();
        s.submissionId = submissionId;
        s.county = county;
        s.city = city;
        s.street = street;
        s.streetNumber = streetNumber;
        s.maxBeds = maxBeds;
        s.maxGuests = maxGuests;
        s.offerType = offerType;
        s.offering = offering;
        s.building = building;
        s.apartments = apartments;
        s.legalized = legalized;
        Instant now = Instant.now();
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    public void setLocationDetails(String settlement, String floor, String cadastralMunicipality,
                                   String cadastralParcelNumber, String accommodationCode,
                                   Boolean lessorResidence, Long accommodationTypeId, UUID coreObjectId) {
        this.settlement = settlement;
        this.floor = floor;
        this.cadastralMunicipality = cadastralMunicipality;
        this.cadastralParcelNumber = cadastralParcelNumber;
        this.accommodationCode = accommodationCode;
        this.lessorResidence = lessorResidence;
        this.accommodationTypeId = accommodationTypeId;
        this.coreObjectId = coreObjectId;
        this.updatedAt = Instant.now();
    }

    public void setConsent(Boolean coOwnerConsent, LocalDate consentDate,
                           LocalDate consentWithdrawalDate) {
        this.coOwnerConsent = coOwnerConsent;
        this.consentDate = consentDate;
        this.consentWithdrawalDate = consentWithdrawalDate;
        this.updatedAt = Instant.now();
    }

    /** Called once, before first save, to bind a newly-built accommodation to its submission. */
    public void linkToSubmission(UUID submissionId) {
        this.submissionId = submissionId;
    }

    public void markHost(boolean value) {
        this.host = value;
        Instant now = Instant.now();
        if (value && this.hostDate == null) {
            this.hostDate = now;
        }
        if (!value && this.hostDate != null && this.hostWithdrawalDate == null) {
            this.hostWithdrawalDate = now;
        }
        this.updatedAt = now;
    }

    public void setSpecDetails(String name, String group, String requestedCategory, String category,
                               String description, String brko, String note, Integer auxiliaryBeds,
                               Boolean lessorDomicile) {
        this.name = name;
        this.group = group;
        this.requestedCategory = requestedCategory;
        this.category = category;
        this.description = description;
        this.brko = brko;
        this.note = note;
        this.auxiliaryBeds = auxiliaryBeds;
        this.lessorDomicile = lessorDomicile;
        this.updatedAt = Instant.now();
    }
}
