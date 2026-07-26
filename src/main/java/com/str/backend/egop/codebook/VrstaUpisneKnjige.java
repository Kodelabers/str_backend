package com.str.backend.egop.codebook;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum VrstaUpisneKnjige {
    NP("NP"),
    UP_I("UP/I"),
    UP_II("UP/II");

    private final String code;

    public static VrstaUpisneKnjige getByCode(String code) {
        for (VrstaUpisneKnjige v : values()) {
            if (v.getCode().equalsIgnoreCase(code)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Nepoznata upisna knjiga: " + code);
    }
}
