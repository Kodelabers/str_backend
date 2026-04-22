package com.str.backend.sso.dto;

import com.str.backend.domain.Ponuda;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateSsoRequest(
        @NotNull UUID coreObjektUuid,
        @Positive int kapacitetKreveta,
        @Positive int kapacitetGostiju,
        @NotNull Ponuda ponuda,
        @Size(max = 8) String kat,
        @Size(max = 16) String brojStana,
        @NotNull @Valid IznajmljivacRequest iznajmljivac
) {}
