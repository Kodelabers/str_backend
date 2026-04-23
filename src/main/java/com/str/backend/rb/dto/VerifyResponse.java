package com.str.backend.rb.dto;

import com.str.backend.domain.Ponuda;
import com.str.backend.domain.RbStatus;

public record VerifyResponse(
        String registracijskiBroj,
        RbStatus status,
        int kapacitet,
        Ponuda tipPonude
) {}
