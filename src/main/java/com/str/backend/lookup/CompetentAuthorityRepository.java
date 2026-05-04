package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface CompetentAuthorityRepository extends JpaRepository<CompetentAuthorityEntity, Long> {
}
