package com.str.backend.attachment;

import java.util.UUID;

/**
 * Tko je prilog učitao ({@code pismeno_prilog.ucitao}) — isti oblici kao akteri revizijskog traga.
 * Po ovome se nevezani prilog veže ili briše: samo onaj tko ga je učitao.
 *
 * <ul>
 *   <li>{@code NIAS:<oib>} — OIB <b>vlasnika</b> za kojeg se radi ({@code EffectiveOibResolver}):
 *       tvrtke kad osoba djeluje u njezino ime, inače same osobe. Vezanje ide uz radnju istog
 *       vlasnika, pa se tvrtkin prilog ne može vezati uz radnju „u svoje ime" ni obrnuto;</li>
 *   <li>{@code LESSOR:<lessorId>} — non-EU iznajmljivač;</li>
 *   <li>{@code INTERNAL:<oib>} — službenik ({@code InternalAccessService#actorFor}).</li>
 * </ul>
 */
public final class Ucitao {

    static final String NIAS_PREFIX = "NIAS:";
    static final String LESSOR_PREFIX = "LESSOR:";
    static final String INTERNAL_PREFIX = "INTERNAL:";

    private Ucitao() {
    }

    public static String nias(String vlasnikOib) {
        return NIAS_PREFIX + vlasnikOib;
    }

    public static String lessor(UUID lessorId) {
        return LESSOR_PREFIX + lessorId;
    }

    /** Prilog službenika ide uz izlazni akt; prilog stranke uz njezin podnesak. */
    public static boolean jeSluzbenik(String ucitao) {
        return ucitao != null && ucitao.startsWith(INTERNAL_PREFIX);
    }
}
