package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface HouseNumberRepository extends JpaRepository<HouseNumberEntity, Long> {

    List<HouseNumberEntity> findByActiveTrueAndStreetIdOrderByName(Long streetId);

    List<HouseNumberEntity> findByActiveTrueAndStreetIdAndNameContainingIgnoreCaseOrderByName(Long streetId, String name);
}
