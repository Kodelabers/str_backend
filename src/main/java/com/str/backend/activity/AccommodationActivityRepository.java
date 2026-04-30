package com.str.backend.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface AccommodationActivityRepository extends JpaRepository<AccommodationActivityEntity, java.util.UUID> {

    @Query("""
            SELECT a FROM AccommodationActivityEntity a
            WHERE (:platformId IS NULL OR a.platformId = :platformId)
              AND (:rn IS NULL OR a.rn = :rn)
              AND (:od IS NULL OR a.periodTo >= :od)
              AND (:toDate IS NULL OR a.periodFrom <= :toDate)
            """)
    List<AccommodationActivityEntity> search(@Param("platformId") Long platformId,
                                             @Param("rn") String rn,
                                             @Param("od") LocalDate od,
                                             @Param("toDate") LocalDate toDate);

    @Modifying
    @Query("DELETE FROM AccommodationActivityEntity a WHERE a.purgeAfter <= :now")
    int purgeExpired(@Param("now") Instant now);
}
