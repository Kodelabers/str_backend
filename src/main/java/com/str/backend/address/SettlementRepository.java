package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface SettlementRepository extends JpaRepository<SettlementEntity, Long> {

    List<SettlementEntity> findByActiveTrueAndMunicipalityIdOrderByName(Long municipalityId);

    List<SettlementEntity> findByActiveTrueAndMunicipalityIdAndNameContainingIgnoreCaseOrderByName(Long municipalityId, String name);
}
