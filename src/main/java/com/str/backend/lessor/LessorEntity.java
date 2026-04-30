package com.str.backend.lessor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "lessor")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class LessorEntity {

    @Id
    @Column(name = "lessor_id", nullable = false, updatable = false)
    private UUID lessorId;

    @Column(name = "representative_oib", length = 11)
    @Setter private String representativeOib;

    @Column(name = "first_name", length = 128, nullable = false, updatable = false)
    private String firstName;

    @Column(name = "last_name", length = 128, nullable = false, updatable = false)
    private String lastName;

    @Column(name = "legal_entity_name")
    @Setter private String legalEntityName;

    @Column(name = "street", length = 128, nullable = false, updatable = false)
    private String street;

    @Column(name = "street_number", length = 16, nullable = false, updatable = false)
    private String streetNumber;

    @Column(name = "place", length = 128, nullable = false, updatable = false)
    private String place;

    @Column(name = "county", length = 128, nullable = false, updatable = false)
    private String county;

    @Column(name = "contact_name")
    @Setter private String contactName;

    @Column(name = "phone_number", length = 32)
    @Setter private String phoneNumber;

    @Column(name = "mobile_number", length = 32)
    @Setter private String mobileNumber;

    @Column(name = "email", nullable = false, updatable = false)
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

    @Column(name = "official_person_id", length = 64)
    @Setter private String officialPersonId;

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
}
