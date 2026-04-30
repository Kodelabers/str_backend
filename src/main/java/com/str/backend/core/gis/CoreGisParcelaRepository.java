package com.str.backend.core.gis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public interface CoreGisParcelaRepository extends JpaRepository<CoreGisParcelaEntity, Long> {

    Optional<CoreGisParcelaEntity> findByKatastarskaOpcinaAndBrojCestice(String katastarskaOpcina,
                                                                        String brojCestice);
}
