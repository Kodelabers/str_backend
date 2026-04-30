package com.str.backend.domain;

public enum Scenario {
    /** STR-1.1: existing accommodation unit — assign RB without a new Submission. */
    S1_EXISTING_UNIT,
    /** STR-1.2: new accommodation unit — registration via external portal (lessor). */
    S2_NEW_UNIT_EXTERNAL,
    /** STR-1.2 variant: new accommodation unit — registration via internal portal (referent). */
    S3_NEW_UNIT_INTERNAL
}
