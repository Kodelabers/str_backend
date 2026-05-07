package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface MunicipalityRepository extends JpaRepository<MunicipalityEntity, Long> {

    List<MunicipalityEntity> findByActiveTrueAndCountyIdOrderByName(Long countyId);

    List<MunicipalityEntity> findByActiveTrueAndCountyIdAndNameContainingIgnoreCaseOrderByName(Long countyId, String name);
}
