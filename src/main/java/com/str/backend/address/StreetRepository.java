package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface StreetRepository extends JpaRepository<StreetEntity, Long> {

    @Query("""
            SELECT u FROM StreetEntity u
            WHERE u.naseljeId = (SELECT s.naMb FROM SettlementEntity s WHERE s.id = :settlementId)
              AND (:q = '' OR LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY u.name
            """)
    List<StreetEntity> findBySettlementIdOrderByName(@Param("settlementId") Long settlementId, @Param("q") String q);
}
