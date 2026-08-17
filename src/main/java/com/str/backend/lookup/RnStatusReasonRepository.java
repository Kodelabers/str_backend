package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Čitanje šifrarnika razloga statusa. Read-only: redovi se pune Liquibase seedom (changeset 124),
 * a kasnija administracija (paljenje/gašenje, natpis) je zaseban korak.
 */
public interface RnStatusReasonRepository extends JpaRepository<RnStatusReasonEntity, Long> {

    /** Svi redovi za kontekst (uključivo neaktivne) — obrazac sam odlučuje što skriva. */
    @Transactional(readOnly = true)
    List<RnStatusReasonEntity> findByContext(String context);

    /** Puni katalog za interni pregled/izvoz, uredno poredan. */
    @Transactional(readOnly = true)
    List<RnStatusReasonEntity> findAllByOrderByContextAscSortOrderAsc();
}
