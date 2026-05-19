package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "str_rn", name = "accommodation_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccommodationTypeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "type_id")
    private Long typeId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "registration_number_allowed", nullable = false)
    private boolean registrationNumberAllowed;

    @Column(name = "group_name", nullable = false)
    private String group;

    public AccommodationTypeEntity(String name, boolean registrationNumberAllowed, String group) {
        this.name = name;
        this.registrationNumberAllowed = registrationNumberAllowed;
        this.group = group;
    }
}
