package com.str.backend.prilog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrilogZahtjevaRepository extends JpaRepository<PrilogZahtjevaEntity, UUID> {
    List<PrilogZahtjevaEntity> findByIdZahtjeva(UUID idZahtjeva);
}
