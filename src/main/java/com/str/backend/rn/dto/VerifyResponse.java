package com.str.backend.rn.dto;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.RnStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class VerifyResponse {

    private String registrationNumber;
    private RnStatus status;
    private int capacity;
    private OfferType offerType;
}
