package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface HouseNumberRepository extends JpaRepository<HouseNumberEntity, Long> {

    @Query("""
            SELECT h FROM HouseNumberEntity h
            WHERE h.ulicaId = :streetId
              AND (:q = '' OR LOWER(h.name) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY h.name
            """)
    List<HouseNumberEntity> findByStreetIdOrderByName(@Param("streetId") Long streetId, @Param("q") String q);
}
