package com.str.backend.sso;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SsoRepository extends JpaRepository<SsoEntity, UUID> {

    List<SsoEntity> findByIdZahtjeva(UUID idZahtjeva);

    Optional<SsoEntity> findByIdCoreObjekt(UUID idCoreObjekt);
}
