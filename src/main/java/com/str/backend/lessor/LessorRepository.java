package com.str.backend.lessor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface LessorRepository extends JpaRepository<LessorEntity, UUID> {

    @Transactional(readOnly = true)
    Optional<LessorEntity> findByUsername(String username);

    @Transactional(readOnly = true)
    Optional<LessorEntity> findByEmail(String email);
}
