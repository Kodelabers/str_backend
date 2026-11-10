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
    /** Rok za očitovanje na prijedlog povlačenja; samo u statusu WITHDRAWAL_PROPOSED (T12). */
    private LocalDate withdrawalDeadline;
    /**
     * Prijedlog povlačenja pokrenut iz suspenzije (T12): broj javno ostaje suspendiran. {@code true} samo u
     * statusu WITHDRAWAL_PROPOSED.
     */
    private boolean prijedlogIzSuspenzije;
}
