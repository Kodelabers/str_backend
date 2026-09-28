package com.str.backend.lessor;

import java.util.Optional;

/**
 * Registar iz kojeg se čitaju adresa i ime fizičke osobe (stavka 2). Točno jedna implementacija
 * je aktivna, po {@code app.oib-registry.enabled}:
 * <ul>
 *   <li>{@code true} → OIB sustav ({@code registries.oib.OibRegistryHttpClient})</li>
 *   <li>{@code false} (default) → eTurizam {@code str.subject*} ({@code str.StrSubjectRegistry}),
 *       dok OIB sustav ne omogući poziv servis-servis</li>
 * </ul>
 */
public interface SubjectRegistry {

    /**
     * @return subjekt, ili {@link Optional#empty()} kad ga registar ne poznaje
     * @throws com.str.backend.exception.ExternalRegistryException kad registar nije dostupan ili
     *         vrati neočekivan odgovor — 503, jednako kao MPGI/DGU
     */
    Optional<RegistrySubject> findByOib(String oib);
}
