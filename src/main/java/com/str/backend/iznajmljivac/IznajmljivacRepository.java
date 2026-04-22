package com.str.backend.iznajmljivac;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface IznajmljivacRepository extends JpaRepository<IznajmljivacEntity, Long> {

    @Transactional(readOnly = true)
    Optional<IznajmljivacEntity> findTopByUuidSsoOrderByCreatedAtDesc(UUID uuidSso);
}
