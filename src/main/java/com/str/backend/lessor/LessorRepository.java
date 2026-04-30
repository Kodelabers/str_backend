package com.str.backend.lessor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LessorRepository extends JpaRepository<LessorEntity, UUID> {
}
