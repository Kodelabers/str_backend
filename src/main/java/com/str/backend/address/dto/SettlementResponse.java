package com.str.backend.address.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SettlementResponse {
    private Long id;
    private String name;
    private String postalCode;
}
