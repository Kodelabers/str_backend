package com.str.backend.str;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface StrSubjectRepository extends JpaRepository<StrSubjectEntity, Long> {

    Optional<StrSubjectEntity> findFirstByJipsAndActiveTrue(String jips);
}
