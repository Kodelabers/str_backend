package com.str.backend.representative;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "legal_representative")
public class LegalRepresentativeEntity {

    public static final String SOURCE_COURT_REGISTRY = "COURT_REGISTRY";
    public static final String SOURCE_MANUAL_ENTRY = "MANUAL";

    @Id
    @Column(name = "representative_id", nullable = false, updatable = false)
    private UUID representativeId;

    @Column(name = "lessor_id", nullable = false, updatable = false)
    private UUID lessorId;

    @Column(name = "oib", length = 11)
    private String oib;

    @Column(name = "first_name", length = 128, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 128, nullable = false)
    private String lastName;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "source", length = 32, nullable = false, updatable = false)
    private String source;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant retrievedAt;

    protected LegalRepresentativeEntity() {
    }

    public static LegalRepresentativeEntity fromCourtRegistry(UUID lessorId, String oib, String firstName,
                                                               String lastName, String address) {
        return create(lessorId, oib, firstName, lastName, address, null, null, SOURCE_COURT_REGISTRY);
    }

    public static LegalRepresentativeEntity manualEntry(UUID lessorId, String oib, String firstName,
                                                        String lastName, String address, String email, String phone) {
        return create(lessorId, oib, firstName, lastName, address, email, phone, SOURCE_MANUAL_ENTRY);
    }

    private static LegalRepresentativeEntity create(UUID lessorId, String oib, String firstName,
                                                     String lastName, String address, String email,
                                                     String phone, String source) {
        LegalRepresentativeEntity e = new LegalRepresentativeEntity();
        e.representativeId = UUID.randomUUID();
        e.lessorId = lessorId;
        e.oib = oib;
        e.firstName = firstName;
        e.lastName = lastName;
        e.address = address;
        e.email = email;
        e.phone = phone;
        e.source = source;
        e.retrievedAt = Instant.now();
        return e;
    }

    public UUID getRepresentativeId() { return representativeId; }
    public UUID getLessorId() { return lessorId; }
    public String getOib() { return oib; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getAddress() { return address; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getSource() { return source; }
    public Instant getRetrievedAt() { return retrievedAt; }
}
