package com.str.backend.rn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RegistrationNumberLogRepository extends JpaRepository<RegistrationNumberLogEntity, UUID> {

    List<RegistrationNumberLogEntity> findByRnOrderByOccurredAtAsc(String rn);
}
