package com.str.backend.rn.dto;

/**
 * Public, advertise-safe projection of an ACTIVE registration number, returned by the
 * verification endpoint (STR-1.4-001). Only fields the public may see — no lessor identity.
 */
public record RnPublicView(
        String rn,
        String accommodationName,
        String category,
        String street,
        String streetNumber,
        String city,
        String group,
        String type
) {}
