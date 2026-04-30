package com.str.backend.registration.dto;

import com.str.backend.domain.Scenario;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class RegistrationResponse {

    private Scenario scenario;
    private UUID lessorId;
    private List<AssignedRb> assignedRbs;

    @Getter
    @AllArgsConstructor
    public static class AssignedRb {
        private UUID accommodationId;
        private String rn;
    }
}
