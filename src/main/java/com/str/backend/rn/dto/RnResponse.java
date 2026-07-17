package com.str.backend.rn.dto;

import com.str.backend.domain.RnStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class RnResponse {

    private String rn;
    private UUID submissionId;
    private UUID accommodationId;
    private RnStatus status;
    private LocalDate issueDate;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalDate suspensionDeadline;
}
