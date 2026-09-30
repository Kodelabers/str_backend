package com.str.backend.registries.eovlastenja;

import java.io.Serializable;

/**
 * Tvrtka koju osoba zastupa prema popisu iz {@code GetNavigationData}. Popis je nepotpisan i služi
 * samo za prikaz: odabir se uvijek potvrđuje potpisanim {@link EOvlastenjaClient#verifyRepresentation}.
 *
 * <p>{@link Serializable} jer se popis kratko čuva u sesiji (Spring Session JDBC).
 */
public record ZastupanaTvrtka(String oib, String naziv) implements Serializable {
}
