package com.str.backend.address.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StreetResponse {
    private Long id;
    private String name;
    private String typeCode;
}
