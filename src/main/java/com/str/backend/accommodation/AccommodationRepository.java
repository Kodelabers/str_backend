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

    /** BPSO statistics: accommodation counts grouped by county name. */
    @Transactional(readOnly = true)
    @Query("SELECT a.county AS county, COUNT(a) AS count FROM AccommodationEntity a GROUP BY a.county")
    List<CountyCount> countByCounty();

    interface CountyCount {
        String getCounty();
        long getCount();
    }
}
