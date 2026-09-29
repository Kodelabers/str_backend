package com.str.backend.registries.eovlastenja;

/**
 * Provjera zakonskog zastupanja pravne osobe kroz e-Ovlaštenja (modul e-Zastupanja).
 * Aktivna je točno jedna implementacija, po {@code app.eovlastenja.enabled}.
 */
public interface EOvlastenjaClient {

    /**
     * @param sesijaId  {@code sesija_id} iz NIAS assertiona; bez njega e-Ovlaštenja vraćaju 203
     * @param personOib OIB prijavljene osobe (iz assertiona)
     * @param legalOib  OIB tvrtke u čije ime osoba želi djelovati ({@code IZVOR_REG=1})
     * @return potvrđeno zastupanje
     * @throws EOvlastenjaException kad zastupanja nema ili je sjednica nevažeća
     * @throws com.str.backend.exception.ExternalRegistryException kad servis nije dostupan ili
     *         je odgovor neispravan (potpis, oblik) — 503
     */
    Zastupanje verifyRepresentation(String sesijaId, String personOib, String legalOib);
}
