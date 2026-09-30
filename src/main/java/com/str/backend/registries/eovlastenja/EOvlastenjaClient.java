package com.str.backend.registries.eovlastenja;

import java.util.List;

/**
 * Provjera zakonskog zastupanja pravne osobe kroz e-Ovlaštenja (modul e-Zastupanja).
 * Aktivna je točno jedna implementacija, po {@code app.eovlastenja.enabled}.
 */
public interface EOvlastenjaClient {

    /**
     * Tvrtke koje osoba zastupa po zakonu ({@code GetNavigationData} — „sadržaj navigacijske
     * trake"), samo {@code IZVOR_REG=1} i samo e-Zastupanja. Odgovor FINA-e <b>nije potpisan</b>,
     * pa popis služi isključivo za prikaz; odabir potvrđuje {@link #verifyRepresentation}.
     *
     * @throws EOvlastenjaException kad je sjednica nevažeća (401)
     * @throws com.str.backend.exception.ExternalRegistryException kad servis nije dostupan, usluga
     *         nema pristup metodi (šifra 100) ili je odgovor neispravan — 503
     */
    List<ZastupanaTvrtka> representedCompanies(String sesijaId, String personOib);

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
