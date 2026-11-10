package com.str.backend.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Hrvatsko vrijeme za datume s pravnim učinkom (rokovi za očitovanje, dan povlačenja, rok ponovnog
 * zahtjeva). Kontejner radi u UTC-u, pa bi {@code LocalDate.now()} između ponoći i 01:00 (02:00 ljeti)
 * dao prethodni dan — upravo kad se vrte noćni poslovi.
 */
public final class Zagreb {

    public static final ZoneId ZONE = ZoneId.of("Europe/Zagreb");

    private Zagreb() {
    }

    /** Današnji datum po hrvatskom vremenu, iz zadanog sata (u testovima fiksnog). */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** Današnji datum po hrvatskom vremenu, iz sistemskog sata. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
