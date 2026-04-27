package com.str.backend.registries;

import java.util.List;

/**
 * STR funkcionalna specifikacija §6.1.3 / §6.1.4 + GO-5: Sudski registar via eGOP.
 * Returns the legal representatives of a pravna osoba identified by OIB.
 */
public interface EgopClient {

    record Zastupnik(String oib, String ime, String prezime, String adresa) {}

    List<Zastupnik> dohvatiZastupnike(String oibPravneOsobe);
}
