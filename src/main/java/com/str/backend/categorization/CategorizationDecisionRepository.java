package com.str.backend.categorization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CategorizationDecisionRepository extends JpaRepository<CategorizationDecisionEntity, UUID> {

    /** Interni pregled za nadležno tijelo — filtar po statusu (SUBMITTED/VERIFIED/REJECTED). */
    @Transactional(readOnly = true)
    Page<CategorizationDecisionEntity> findByStatus(CategorizationDecisionStatus status, Pageable pageable);

    /**
     * Zapisi koje na korisnikovom popisu treba prikazati kao privremeno rješenje: još nisu
     * odbijeni i još nemaju objekt u eTurizmu. Kad {@code facilityId} bude popunjen, objekt
     * dolazi iz {@code str.facility} i ovaj zapis se više ne prikazuje zasebno.
     */
    @Transactional(readOnly = true)
    List<CategorizationDecisionEntity> findByLessorOibAndFacilityIdIsNullAndStatusNotOrderByUploadedAtDesc(
            String lessorOib, CategorizationDecisionStatus status);

    @Transactional(readOnly = true)
    List<CategorizationDecisionEntity> findByLessorOibOrderByUploadedAtDesc(String lessorOib);
}
