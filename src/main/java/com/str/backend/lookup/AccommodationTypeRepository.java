package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccommodationTypeRepository extends JpaRepository<AccommodationTypeEntity, Long> {
    List<AccommodationTypeEntity> findAllByRegistrationNumberAllowedTrue();
}
