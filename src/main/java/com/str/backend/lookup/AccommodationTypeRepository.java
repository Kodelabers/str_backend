package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AccommodationTypeRepository extends JpaRepository<AccommodationTypeEntity, Long> {
    List<AccommodationTypeEntity> findAllByRegistrationNumberAllowedTrue();

    /**
     * Šifre vrsta koje ovaj servis poznaje. Popis objekata s eTurizam strane filtrira po njima:
     * iznajmljivač tamo može imati i restoran, agenciju ili turističkog vodiča, a to na NIAS
     * dashboard ne ide. Šifru imaju samo vrste privatnog smještaja (changeset 060), pa je popis
     * upravo FS_SOBA / FS_APARTMAN / FS_STUDIO_APARTMAN / FS_KUCA_ZA_ODMOR.
     */
    @Query("SELECT t.code FROM AccommodationTypeEntity t WHERE t.code IS NOT NULL")
    List<String> findAllCodes();

    /**
     * Razrješava vrstu po stabilnoj šifri (FS_SOBA, FS_APARTMAN, ...) — tako tuStart i
     * ostali pozivatelji ne moraju znati {@code type_id}, koji se razlikuje među okolinama.
     * Šifra je unique (changeset 060), pa je rezultat najviše jedan.
     */
    Optional<AccommodationTypeEntity> findByCodeIgnoreCase(String code);
}
