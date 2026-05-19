package com.str.backend.address.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CountryResponse {
    private Long id;
    private String name;
    private String iso2Alpha;
}
