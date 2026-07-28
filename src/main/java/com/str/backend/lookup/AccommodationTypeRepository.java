package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccommodationTypeRepository extends JpaRepository<AccommodationTypeEntity, Long> {
    List<AccommodationTypeEntity> findAllByRegistrationNumberAllowedTrue();

    /**
     * Razrješava vrstu po stabilnoj šifri (FS_SOBA, FS_APARTMAN, ...) — tako tuStart i
     * ostali pozivatelji ne moraju znati {@code type_id}, koji se razlikuje među okolinama.
     * Šifra je unique (changeset 060), pa je rezultat najviše jedan.
     */
    Optional<AccommodationTypeEntity> findByCodeIgnoreCase(String code);
}
