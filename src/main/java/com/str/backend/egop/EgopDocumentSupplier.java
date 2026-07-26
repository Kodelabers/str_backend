package com.str.backend.egop;

import com.str.backend.document.FilingReference;

/**
 * Renderira PDF za pojedino pismeno, pozvan <b>tek nakon</b> što pismeno postoji u eGOP-u.
 *
 * <p>Redoslijed je obrnut od očekivanog: urudžbeni broj nastaje kreiranjem pismena, a mora
 * biti otisnut u PDF-u koji se tom istom pismenu prilaže. Zato dvije metode umjesto jednog
 * {@code Function<String, byte[]>} — ulazno i izlazno pismeno dobivaju <b>različite</b>
 * URBROJ-eve, pa jedan callback ne bi mogao poslužiti obama.
 */
public interface EgopDocumentSupplier {

    /** Zahtjev za registracijski broj (ulazno pismeno). */
    byte[] zahtjev(FilingReference filing);

    /** Obavijest o dodjeli registracijskog broja (izlazno pismeno). */
    byte[] obavijestODodjeli(FilingReference filing);
}
