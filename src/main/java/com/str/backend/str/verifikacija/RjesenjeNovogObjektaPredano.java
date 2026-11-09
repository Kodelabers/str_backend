package com.str.backend.str.verifikacija;

/**
 * Uz RB novog objekta predano je rješenje o kategorizaciji i zabilježena obavijest o žurnoj
 * verifikaciji ({@link VerifikacijaOutbox#zabiljeziNoviObjekt}). Objavljuje se u transakciji predaje;
 * {@link EturizamVerifikacijaListener} ga prima tek nakon njenog commita i radi prvi pokušaj upisa.
 */
public record RjesenjeNovogObjektaPredano(String rn) {
}
