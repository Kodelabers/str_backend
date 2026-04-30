package com.str.backend.registries;

import java.util.Optional;

/**
 * STR funkcionalna specifikacija §6.1: GIS — geokod/parcele.
 * Read-only obogaćivanje podataka iz core.gis_parcela: provjera legalnosti
 * objekta, površine, namjene po katastarskoj općini i broju čestice.
 */
public interface GisClient {

    record Parcela(String katastarskaOpcina, String brojCestice, Integer povrsinaM2,
                   String namjena, Boolean legalanObjekt) {}

    Optional<Parcela> dohvatiParcelu(String katastarskaOpcina, String brojCestice);
}
