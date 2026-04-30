package com.str.backend.representative;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LegalRepresentativeRepository extends JpaRepository<LegalRepresentativeEntity, UUID> {
    List<LegalRepresentativeEntity> findByLessorId(UUID lessorId);
}
