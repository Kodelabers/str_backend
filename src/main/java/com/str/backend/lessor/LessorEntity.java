package com.str.backend.lessor;

import com.str.backend.domain.LessorApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "lessor")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class LessorEntity {

    @Id
    @Column(name = "lessor_id", nullable = false, updatable = false)
    private UUID lessorId;

    @Column(name = "lessor_oib", length = 11)
    @Setter private String lessorOib;

    @Column(name = "representative_oib", length = 11)
    @Setter private String representativeOib;

    @Column(name = "first_name", length = 128, nullable = false, updatable = false)
    private String firstName;

    @Column(name = "last_name", length = 128, nullable = false, updatable = false)
    private String lastName;

    @Column(name = "legal_entity_name")
    @Setter private String legalEntityName;

    @Column(name = "street", length = 500, nullable = false, updatable = false)
    private String street;

    @Column(name = "street_number", length = 16, updatable = false)
    private String streetNumber;

    @Column(name = "place", length = 128, updatable = false)
    private String place;

    @Column(name = "county", length = 128, updatable = false)
    private String county;

    @Column(name = "date_of_birth", updatable = false)
    private LocalDate dateOfBirth;

    @Column(name = "country_of_residence_id", updatable = false)
    private Integer countryOfResidenceId;

    @Column(name = "tax_number", length = 64, updatable = false)
    private String taxNumber;

    @Column(name = "is_legal_entity_owner", nullable = false, updatable = false)
    private boolean legalEntityOwner;

    @Column(name = "legal_entity_country_id", updatable = false)
    private Integer legalEntityCountryId;

    @Column(name = "legal_entity_city", length = 200, updatable = false)
    private String legalEntityCity;

    @Column(name = "legal_entity_registration_number", length = 40, updatable = false)
    private String legalEntityRegistrationNumber;

    @Column(name = "contact_name")
    @Setter private String contactName;

    @Column(name = "phone_number", length = 32)
    @Setter private String phoneNumber;

    @Column(name = "mobile_number", length = 32)
    @Setter private String mobileNumber;

    @Column(name = "email", updatable = false)
    private String email;

    @Column(name = "legal_representative_name")
    @Setter private String legalRepresentativeName;

    @Column(name = "representative_email")
    @Setter private String representativeEmail;

    @Column(name = "representative_phone", length = 32)
    @Setter private String representativePhone;

    @Column(name = "representative_address", length = 255)
    private String representativeAddress;

    @Column(name = "contact_note", length = 1024)
    @Setter private String contactNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_status", length = 32)
    @Setter private LessorApplicationStatus applicationStatus;

    @Column(name = "official_person_id", length = 64)
    @Setter private String officialPersonId;

    @Column(name = "username", length = 255, updatable = false)
    private String username;

    @Column(name = "password_hash", length = 255)
    @Setter private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static LessorEntity create(String firstName, String lastName, String street, String streetNumber,
                                      String place, String county, String email) {
        LessorEntity e = new LessorEntity();
        e.lessorId = UUID.randomUUID();
        e.firstName = firstName;
        e.lastName = lastName;
        e.street = street;
        e.streetNumber = streetNumber;
        e.place = place;
        e.county = county;
        e.email = email;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public static LessorEntity createNonEu(String firstName, String lastName, String street, String streetNumber,
                                           String place, String county, String email,
                                           String username, String passwordHash) {
        LessorEntity e = create(firstName, lastName, street, streetNumber, place, county, email);
        e.username = username;
        e.passwordHash = passwordHash;
        return e;
    }

    public static LessorEntity createNonEuRegistration(
            String firstName, String lastName,
            String street, String email,
            String username, String passwordHash,
            LocalDate dateOfBirth, Integer countryOfResidenceId,
            String taxNumber, String mobileNumber) {

        LessorEntity e = new LessorEntity();
        e.lessorId = UUID.randomUUID();
        e.firstName = firstName;
        e.lastName = lastName;
        e.street = street;
        e.email = email;
        e.username = username;
        e.passwordHash = passwordHash;
        e.dateOfBirth = dateOfBirth;
        e.countryOfResidenceId = countryOfResidenceId;
        e.taxNumber = taxNumber;
        e.mobileNumber = mobileNumber;
        e.applicationStatus = LessorApplicationStatus.PENDING;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    /**
     * Applies the optional "legal entity owner" group captured during non-EU self-registration.
     * Call before the entity is first persisted — the columns are {@code updatable = false},
     * so they are written on INSERT only (this is construction, not a mutation, so timestamps
     * are left as set by the factory). Reuses {@code legalEntityName} for the name; note that
     * column stays mutable for the legacy {@code StrLessorLookupService} flow.
     */
    public void applyLegalEntityOwner(String name, Integer countryId, String city, String registrationNumber) {
        this.legalEntityOwner = true;
        this.legalEntityName = name;
        this.legalEntityCountryId = countryId;
        this.legalEntityCity = city;
        this.legalEntityRegistrationNumber = registrationNumber;
    }

    public void setLegalEntity(String representativeOib, String legalEntityName, String legalRepresentativeName,
                               String representativeEmail, String representativePhone) {
        this.representativeOib = representativeOib;
        this.legalEntityName = legalEntityName;
        this.legalRepresentativeName = legalRepresentativeName;
        this.representativeEmail = representativeEmail;
        this.representativePhone = representativePhone;
        this.updatedAt = Instant.now();
    }

    public void setContact(String contactName, String phoneNumber, String mobileNumber,
                           String contactNote) {
        this.contactName = contactName;
        this.phoneNumber = phoneNumber;
        this.mobileNumber = mobileNumber;
        this.contactNote = contactNote;
        this.updatedAt = Instant.now();
    }

    public void setRepresentativeAddress(String representativeAddress) {
        this.representativeAddress = representativeAddress;
        this.updatedAt = Instant.now();
    }

    public void approveRegistration(String actorId) {
        this.applicationStatus = LessorApplicationStatus.ACCEPTED;
        if (actorId != null) this.officialPersonId = actorId;
        this.updatedAt = Instant.now();
    }

    public void rejectRegistration(String actorId) {
        this.applicationStatus = LessorApplicationStatus.REJECTED;
        if (actorId != null) this.officialPersonId = actorId;
        this.updatedAt = Instant.now();
    }
}
