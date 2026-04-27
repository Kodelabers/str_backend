package com.str.backend.rb.dto;

import com.str.backend.domain.Ponuda;
import com.str.backend.domain.RbStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class VerifyResponse {

    private String registracijskiBroj;
    private RbStatus status;
    private int kapacitet;
    private Ponuda tipPonude;
}
