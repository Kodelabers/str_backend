package com.str.backend.sso;

import com.str.backend.domain.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SsoRepository extends JpaRepository<SsoEntity, UUID> {

    Optional<SsoEntity> findByRegistracijskiBroj(String registracijskiBroj);

    boolean existsByRegistracijskiBroj(String registracijskiBroj);

    @Query("select s from SsoEntity s where s.status = :status")
    List<SsoEntity> findByStatus(@Param("status") Status status);
}
