package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface CountyRepository extends JpaRepository<CountyEntity, Long> {

    List<CountyEntity> findByActiveTrueOrderByName();

    List<CountyEntity> findByActiveTrueAndNameContainingIgnoreCaseOrderByName(String name);
}
