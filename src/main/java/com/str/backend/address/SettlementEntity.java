package com.str.backend.address;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "str", name = "settlement")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SettlementEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "postal_code", updatable = false)
    private String postalCode;

    @Column(name = "municipality_id", nullable = false, updatable = false)
    private Long municipalityId;

    @Column(name = "active", nullable = false, updatable = false)
    private boolean active;
}
