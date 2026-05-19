package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubmissionTypeRepository extends JpaRepository<SubmissionTypeEntity, Long> {

    Optional<SubmissionTypeEntity> findByCode(String code);
}
