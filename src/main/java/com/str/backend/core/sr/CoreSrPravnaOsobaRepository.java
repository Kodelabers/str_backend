package com.str.backend.core.sr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public interface CoreSrPravnaOsobaRepository extends JpaRepository<CoreSrPravnaOsobaEntity, String> {
}
