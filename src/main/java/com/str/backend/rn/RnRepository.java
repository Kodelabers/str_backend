package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RnRepository extends JpaRepository<RnEntity, String> {

    boolean existsByRn(String rn);

    List<RnEntity> findByAccommodationId(UUID accommodationId);

    List<RnEntity> findBySubmissionId(UUID submissionId);

    Optional<RnEntity> findTopByAccommodationIdAndStatusOrderByCreatedAtDesc(UUID accommodationId, RnStatus status);

    List<RnEntity> findByStatusInOrderByUpdatedAtDesc(List<RnStatus> statuses);
}
