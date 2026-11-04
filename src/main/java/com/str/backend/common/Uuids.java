package com.str.backend.common;

import java.util.Optional;
import java.util.UUID;

/** UUID iz teksta bez iznimke — npr. {@code egop_pismeno.act_ref}, koji je {@code log_id} ili oznaka. */
public final class Uuids {

    private Uuids() {
    }

    /** Prazno za {@code null} i tekst koji nije UUID. */
    public static Optional<UUID> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
