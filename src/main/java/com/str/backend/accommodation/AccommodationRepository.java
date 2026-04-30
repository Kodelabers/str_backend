package com.str.backend.accommodation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccommodationRepository extends JpaRepository<AccommodationEntity, UUID> {

    List<AccommodationEntity> findBySubmissionId(UUID submissionId);

    Optional<AccommodationEntity> findByCoreObjectId(UUID coreObjectId);
}
