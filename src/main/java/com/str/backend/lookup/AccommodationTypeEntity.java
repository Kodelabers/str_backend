package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "str", name = "accommodation_type")
public class AccommodationTypeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "type_id")
    private Long typeId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "registration_number_allowed", nullable = false)
    private boolean registrationNumberAllowed;

    protected AccommodationTypeEntity() {
    }

    public AccommodationTypeEntity(String name, boolean registrationNumberAllowed) {
        this.name = name;
        this.registrationNumberAllowed = registrationNumberAllowed;
    }

    public Long getTypeId() { return typeId; }
    public String getName() { return name; }
    public boolean isRegistrationNumberAllowed() { return registrationNumberAllowed; }
}
