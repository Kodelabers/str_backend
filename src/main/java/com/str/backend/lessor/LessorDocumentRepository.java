package com.str.backend.lessor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LessorDocumentRepository extends JpaRepository<LessorDocumentEntity, UUID> {}
