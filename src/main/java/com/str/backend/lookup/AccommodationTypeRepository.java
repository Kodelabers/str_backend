package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccommodationTypeRepository extends JpaRepository<AccommodationTypeEntity, Long> {
    List<AccommodationTypeEntity> findAllByRegistrationNumberAllowedTrue();

    /** Stavke s pravom na RB iz zadanih skupina — izbornik za subjekt (T13). */
    List<AccommodationTypeEntity> findAllByRegistrationNumberAllowedTrueAndGroupIn(Collection<String> groups);

    /**
     * Šifre ({@code FS_*} vrste, {@code FT_*} skupine) iz zadanih skupina. Popis objekata s eTurizam
     * strane filtrira po njima: iznajmljivač tamo može imati i ono što subjekt njegove vrste u STR-u
     * ne prijavljuje (turistički vodič, objekt u domaćinstvu za tvrtku…), a to na dashboard ne ide.
     */
    @Query("SELECT t.code FROM AccommodationTypeEntity t WHERE t.code IS NOT NULL AND t.group IN :groups")
    List<String> findCodesByGroupIn(Collection<String> groups);

    /**
     * Razrješava vrstu po stabilnoj šifri (FS_SOBA, FS_APARTMAN, …, ili FT_* za skupinu) — tako tuStart i
     * ostali pozivatelji ne moraju znati {@code type_id}, koji se razlikuje među okolinama.
     * Šifra je unique (changeset 060), pa je rezultat najviše jedan.
     */
    Optional<AccommodationTypeEntity> findByCodeIgnoreCase(String code);
}
