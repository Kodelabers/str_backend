package com.str.backend.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Transactional(readOnly = true)
public interface CoreObjektRepository extends JpaRepository<CoreObjektEntity, UUID> {
}
