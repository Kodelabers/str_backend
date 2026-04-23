package com.str.backend.rb;

import com.str.backend.domain.RbStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RbRepository extends JpaRepository<RbEntity, String> {

    boolean existsByRb(String rb);

    List<RbEntity> findByIdSso(UUID idSso);

    List<RbEntity> findByIdZahtjeva(UUID idZahtjeva);

    Optional<RbEntity> findTopByIdSsoAndStatusOrderByCreatedAtDesc(UUID idSso, RbStatus status);
}
