package com.str.backend.registracija.dto;

import com.str.backend.domain.Scenarij;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RegistracijaRequest {

    @NotNull
    private Scenarij scenarij;

    @NotNull
    @Valid
    private IznajmljivacRequest iznajmljivac;

    private Long idNadleznogTijela;

    @NotEmpty
    @Valid
    private List<SsoRequest> sso;
}
