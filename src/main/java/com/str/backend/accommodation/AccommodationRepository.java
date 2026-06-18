package com.str.backend.accommodation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccommodationRepository extends JpaRepository<AccommodationEntity, UUID> {

    List<AccommodationEntity> findBySubmissionId(UUID submissionId);

    Optional<AccommodationEntity> findByCoreObjectId(UUID coreObjectId);

    /** STR statistics: accommodation counts grouped by county name. */
    @Transactional(readOnly = true)
    @Query("SELECT a.county AS county, COUNT(a) AS count FROM AccommodationEntity a GROUP BY a.county")
    List<CountyCount> countByCounty();

    /** Lookup: distinct county names present in accommodation data, alphabetically sorted. */
    @Transactional(readOnly = true)
    @Query("SELECT DISTINCT a.county FROM AccommodationEntity a WHERE a.county IS NOT NULL ORDER BY a.county")
    List<String> findDistinctCountiesOrderByName();

    interface CountyCount {
        String getCounty();
        long getCount();
    }
}
