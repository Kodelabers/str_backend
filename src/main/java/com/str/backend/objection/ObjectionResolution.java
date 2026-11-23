package com.str.backend.objection;

/**
 * Filtar „Status" na popisu očitovanja i prigovora. Neriješeno je ono što još traži radnju službenika
 * ({@link SuspensionObjectionRepository#UNRESOLVED}): predan podnesak, ili podnesak u županijskom postupku
 * koji još nije urudžbiran, i kad ga je sustav zatvorio. Sve ostalo je riješeno.
 */
public enum ObjectionResolution {
    UNRESOLVED,
    RESOLVED,
    ALL
}
