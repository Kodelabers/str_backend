package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface MunicipalityRepository extends JpaRepository<MunicipalityEntity, Long> {

    @Query("""
            SELECT m FROM MunicipalityEntity m
            WHERE m.zuRb = (SELECT c.zuRb FROM CountyEntity c WHERE c.id = :countyId)
              AND (:q = '' OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY m.name
            """)
    List<MunicipalityEntity> findByCountyIdOrderByName(@Param("countyId") Long countyId, @Param("q") String q);
}
