package com.str.backend.registration.dto;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;

import java.time.LocalDate;

public interface AccommodationRequest {
    String name();
    String typeId();
    Long countyId();
    String cityId();
    String settlementId();
    String street();
    String streetNumber();
    String houseNumberCode();
    String postalCode();
    int maxBeds();
    int maxGuests();
    OfferType offerType();
    Offering offering();
    Boolean building();
    String floor();
    Boolean apartments();
    Boolean legalized();
    Boolean lessorResidence();
    Boolean coOwnerConsent();
    LocalDate consentDate();
    LocalDate consentWithdrawalDate();
    Boolean host();
}
