package com.str.backend.registration.dto;

import com.str.backend.domain.Scenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RegistrationRequest {

    @NotNull
    private Scenario scenario;

    @NotNull
    @Valid
    private LessorRequest lessor;

    private Long competentAuthorityId;

    @NotEmpty
    @Valid
    private List<AccommodationRequest> accommodations;
}
