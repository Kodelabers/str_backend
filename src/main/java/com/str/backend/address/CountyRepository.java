package com.str.backend.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface CountyRepository extends JpaRepository<CountyEntity, Long> {

    List<CountyEntity> findAllByOrderByZuRb();

    List<CountyEntity> findByNameContainingIgnoreCaseOrderByZuRb(String name);

    Optional<CountyEntity> findByName(String name);
}
