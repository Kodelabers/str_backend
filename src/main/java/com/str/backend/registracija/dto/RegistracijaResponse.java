package com.str.backend.registracija.dto;

import com.str.backend.domain.Scenarij;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class RegistracijaResponse {

    private Scenarij scenarij;
    private UUID idIznajmljivaca;
    private List<DodijeljeniRb> dodijeljeniRb;

    @Getter
    @AllArgsConstructor
    public static class DodijeljeniRb {
        private UUID idSso;
        private String rb;
    }
}
