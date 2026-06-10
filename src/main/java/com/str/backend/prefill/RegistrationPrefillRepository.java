package com.str.backend.prefill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegistrationPrefillRepository extends JpaRepository<RegistrationPrefillEntity, UUID> {
}
