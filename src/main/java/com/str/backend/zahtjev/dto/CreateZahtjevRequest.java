package com.str.backend.zahtjev.dto;

import com.str.backend.domain.Kanal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateZahtjevRequest(
        @NotNull UUID idIznajmljivaca,
        @NotNull Kanal kanal,
        @NotNull @Size(max = 32) String oznakaVrste,
        Long idNadleznogTijela,
        @NotEmpty @Valid List<SsoRequest> sso
) {}
