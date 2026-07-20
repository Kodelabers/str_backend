package com.str.backend.guest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<GuestEntity, UUID> {

    List<GuestEntity> findByActivityId(UUID activityId);

    /**
     * STR-3.2: options for the "guest country" filter. Derived from the values actually present
     * in the data rather than from a codebook, so the dropdown can never offer a country that
     * yields no results — same approach as the county lookup.
     */
    @Transactional(readOnly = true)
    @Query("SELECT DISTINCT g.country FROM GuestEntity g WHERE g.country <> '' ORDER BY g.country")
    List<String> findDistinctCountriesOrderByName();
}
