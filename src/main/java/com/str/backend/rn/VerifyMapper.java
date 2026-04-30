package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.rn.dto.VerifyResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VerifyMapper {

    @Mapping(source = "rn.rn", target = "registrationNumber")
    @Mapping(source = "rn.status", target = "status")
    @Mapping(source = "accommodation.maxGuests", target = "capacity")
    @Mapping(source = "accommodation.offerType", target = "offerType")
    VerifyResponse toResponse(RnEntity rn, AccommodationEntity accommodation);
}
