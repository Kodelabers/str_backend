package com.str.backend.lessor;

import java.util.Optional;

/**
 * Registar iz kojeg se čitaju adresa i ime fizičke osobe (stavka 2). Koja se implementacija
 * injektira, odlučuje {@code app.oib-registry.enabled}:
 * <ul>
 *   <li>{@code true} → OIB sustav ({@code registries.oib.OibSubjectRegistry}, {@code @Primary}),
 *       s eTurizmom kao zamjenskim izvorom kad OIB sustav osobu ne zna ili nije dostupan</li>
 *   <li>{@code false} (default) → eTurizam {@code str.subject*} ({@code str.StrSubjectRegistry})</li>
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
