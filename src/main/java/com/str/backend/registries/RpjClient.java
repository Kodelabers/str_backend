package com.str.backend.registries;

import java.util.Optional;

/**
 * STR funkcionalna specifikacija §6.1: RPJ — Registar prostornih jedinica.
 * Read-only normalizacija adrese (županija/grad/naselje/ulica/kućni broj
 * → kanonski oblik + poštanski broj) iz core.rpj_adresa.
 */
public interface RpjClient {

    record Adresa(String zupanija, String grad, String naselje, String ulica,
                  String kucniBroj, String postanskiBroj) {}

    Optional<Adresa> normalizirajAdresu(String zupanija, String grad, String ulica, String kucniBroj);
}
