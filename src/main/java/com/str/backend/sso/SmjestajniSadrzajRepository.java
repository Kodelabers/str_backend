package com.str.backend.sso;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmjestajniSadrzajRepository extends JpaRepository<SmjestajniSadrzajEntity, UUID> {

    List<SmjestajniSadrzajEntity> findByIdSso(UUID idSso);

    Optional<SmjestajniSadrzajEntity> findByRb(String rb);
}
