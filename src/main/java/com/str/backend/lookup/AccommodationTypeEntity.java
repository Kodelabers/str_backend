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

    /**
     * Stabilna šifra vrste smještaja (FS_SOBA, FS_APARTMAN, ...). Za razliku od naziva
     * i {@code typeId}, koji se razlikuju među okolinama, šifra je ono na što se vanjske
     * integracije smiju vezati. Popunjava je Liquibase (changeset 060), pa je nullable —
     * vrsta dodana izvan migracije ostaje bez šifre umjesto da obori insert.
     */
    @Column(name = "code")
    private String code;

    public AccommodationTypeEntity(String name, boolean registrationNumberAllowed, String group) {
        this(name, registrationNumberAllowed, group, null);
    }

    public AccommodationTypeEntity(String name, boolean registrationNumberAllowed, String group, String code) {
        this.name = name;
        this.registrationNumberAllowed = registrationNumberAllowed;
        this.group = group;
        this.code = code;
    }
}
