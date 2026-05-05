package com.str.backend.str;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(schema = "str", name = "address")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class StrAddressEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "active", nullable = false, updatable = false)
    private boolean active;

    @Column(name = "country_id", updatable = false)
    private Long countryId;

    @Column(name = "settlement_id", updatable = false)
    private Long settlementId;

    @Column(name = "street_id", updatable = false)
    private Long streetId;

    @Column(name = "house_number_id", updatable = false)
    private Long houseNumberId;

    @Column(name = "county", updatable = false)
    private String county;

    @Column(name = "county_id", updatable = false)
    private Long countyId;

    @Column(name = "municipality", updatable = false)
    private String municipality;

    @Column(name = "municipality_id", updatable = false)
    private Long municipalityId;

    @Column(name = "postal_code", updatable = false)
    private String postalCode;

    @Column(name = "settlement", updatable = false)
    private String settlement;

    @Column(name = "street", updatable = false)
    private String street;

    @Column(name = "house_number", updatable = false)
    private String houseNumber;

    @Column(name = "full_address", updatable = false)
    private String fullAddress;

    @Column(name = "address_description", updatable = false)
    private String addressDescription;
}
