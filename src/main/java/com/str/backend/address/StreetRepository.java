package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface StreetRepository extends JpaRepository<StreetEntity, Long> {

    List<StreetEntity> findByActiveTrueAndSettlementIdOrderByName(Long settlementId);

    List<StreetEntity> findByActiveTrueAndSettlementIdAndNameContainingIgnoreCaseOrderByName(Long settlementId, String name);
}
