package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface CountryRepository extends JpaRepository<CountryEntity, Long> {

    List<CountryEntity> findByActiveTrueOrderByName();

    List<CountryEntity> findByActiveTrueAndNameContainingIgnoreCaseOrderByName(String name);
}
