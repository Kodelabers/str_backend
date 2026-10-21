package com.str.backend.kp.client;

import java.time.Instant;
import java.util.List;

/**
 * Odgovor na slanje. {@code sifre} su šifre odbijanja iz omotnice (M…) i iz poslovnog odgovora
 * (K… za FO, Z… za PS); uspjeh je odgovor bez ijedne.
 *
 * @param obradeno kad je KP obradio poruku; {@code null} ako ga odgovor ne nosi
 * @param opis     tekstovi grešaka s GUID-om pojave, za log i stupac {@code error}
 * @param gsbId    identifikator poruke u SZP-u — po njemu helpdesk APIS-a traži incident
 */
public record KpOdgovor(List<String> sifre, String opis, Instant obradeno, String gsbId) {

    public KpOdgovor {
        sifre = List.copyOf(sifre);
    }

    public boolean ok() {
        return sifre.isEmpty();
    }
}
