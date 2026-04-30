package com.str.backend.registries;

import java.util.List;
import java.util.Optional;

/**
 * STR funkcionalna specifikacija §6.1.3 / §6.1.4 + GO-5: Sudski registar.
 * Read-only podaci o pravnoj osobi po OIB-u (naziv, sjedište, zastupnici).
 */
public interface SrClient {

    record PravnaOsoba(String oib, String naziv, String sjediste, List<String> zastupnici) {}

    Optional<PravnaOsoba> dohvatiPravnuOsobu(String oib);
}
