package com.str.backend.registration.dto;

import com.str.backend.domain.County;
import com.str.backend.domain.OfferType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RegistrationRequest {

    private String name;
    private String typeId;

    @NotNull private County county;
    @NotNull private String cityId;
    private String settlementId;

    @NotNull private String street;
    @NotNull private String streetNumber;
    private String postalCode;

    @Min(1) private int maxBeds;
    @Min(1) private int maxGuests;

    @NotNull private OfferType offerType;
}
