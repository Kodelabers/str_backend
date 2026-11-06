package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface OnlinePlatformRepository extends JpaRepository<OnlinePlatformEntity, Long> {

    List<OnlinePlatformEntity> findByActiveTrueOrderByName();

    /** Platforma SDEP-a po nazivu, bez razlike velikih i malih slova (integracijski API, T3). */
    Optional<OnlinePlatformEntity> findFirstByActiveTrueAndNameIgnoreCase(String name);
}
