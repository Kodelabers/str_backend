package com.str.backend.lessor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LessorDocumentRepository extends JpaRepository<LessorDocumentEntity, UUID> {

    @Transactional(readOnly = true)
    Optional<LessorDocumentEntity> findByLessorId(UUID lessorId);
}
