package com.str.backend.egop.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * eGOP kodovi koji nisu greške nego validni odgovori "entitet ne postoji" —
 * temelj obrasca "provjeri pa kreiraj" (subjekt/predmet).
 */
@Getter
@RequiredArgsConstructor
public enum EgopNotErrorCodes {

    SUBJECT_NOT_FOUND(-300),
    BUSINESS_CASE_NOT_FOUND(-100);

    private final int code;
}
