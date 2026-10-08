package com.str.backend.lessor;

import java.util.Optional;

/**
 * Registar iz kojeg se, kad NIAS osoba djeluje u ime tvrtke, čitaju sjedište tvrtke i ime i adresa
 * njezina zastupnika. Točno jedna implementacija je aktivna, po
 * {@code app.oib-registry.legal-entity-enabled}:
 * <ul>
 *   <li>{@code true} → OIB sustav ({@code registries.oib.OibLegalEntityRegistry})</li>
 *   <li>{@code false} (default) → {@link UnavailableLegalEntityRegistry}: ništa se ne dohvaća i
 *       forma umjesto sjedišta prikazuje napomenu</li>
 * </ul>
 *
 * <p>Zastavica je namjerno odvojena od {@code app.oib-registry.enabled}: uključivanje OIB sustava
 * za tvrtke ne smije promijeniti izvor podataka fizičke osobe ({@link SubjectRegistry}).
 */
public interface LegalEntityRegistry {

    /**
     * @return tvrtka, ili {@link Optional#empty()} kad je registar ne poznaje
     * @throws com.str.backend.exception.ExternalRegistryException kad registar nije dostupan
     */
    Optional<RegistryLegalEntity> findLegalEntity(String oib);

    /**
     * Fizička osoba — zastupnik tvrtke.
     *
     * @return osoba, ili {@link Optional#empty()} kad je registar ne poznaje
     * @throws com.str.backend.exception.ExternalRegistryException kad registar nije dostupan
     */
    Optional<RegistrySubject> findPerson(String oib);
}
