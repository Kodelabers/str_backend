package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface CountryRepository extends JpaRepository<CountryEntity, Long> {

    List<CountryEntity> findByActiveTrueOrderByName();

    List<CountryEntity> findByActiveTrueAndNameContainingIgnoreCaseOrderByName(String name);

    /**
     * Država po ISO 3166-1 alpha-2 kodu ({@code str.country.iso2_alpha}). Ima li više redaka s
     * istim kodom, uzima se onaj s najmanjim {@code id} — isto bira i changeset 134.
     */
    Optional<CountryEntity> findFirstByIso2AlphaIgnoreCaseOrderByIdAsc(String iso2Alpha);
}
