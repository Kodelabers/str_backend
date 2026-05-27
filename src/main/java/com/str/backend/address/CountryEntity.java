package com.str.backend.address;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * TEMPORARY: still mapped to str.country because dev rpj_dgu.drzava only has 1 row
 * (Republika Hrvatska). Switch to rpj_dgu.drzava once GIS populates the registry.
 */
@Entity
@Immutable
@Table(schema = "str", name = "country")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CountryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "iso2_alpha", updatable = false)
    private String iso2Alpha;

    @Column(name = "active", nullable = false, updatable = false)
    private boolean active;
}
