package com.str.backend.registration.dto;

import com.str.backend.domain.OfferType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RegistrationRequest {

    private String name;
    private String typeId;

    @NotNull private String countyId;
    @NotNull private String cityId;
    private String settlementId;

    @NotNull private String street;
    @NotNull private String streetNumber;
    private String postalCode;

    private Integer floor;
    @Min(1) private int maxBeds;
    @Min(1) private int maxGuests;

    @NotNull private OfferType offerType;
    private boolean building;
    private String buildingType;
    private Integer apartmentCount;

    private boolean legalized;
    private Boolean coOwnerConsent;
    private LocalDate consentDate;
}
