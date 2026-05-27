package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface OnlinePlatformRepository extends JpaRepository<OnlinePlatformEntity, Long> {

    List<OnlinePlatformEntity> findByActiveTrueOrderByName();
}
