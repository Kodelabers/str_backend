package com.str.backend.str;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface StrFacilityRepository extends JpaRepository<StrFacilityEntity, Long> {

    long countByActiveTrue();
}
