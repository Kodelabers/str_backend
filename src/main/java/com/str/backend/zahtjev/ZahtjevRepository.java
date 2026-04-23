package com.str.backend.zahtjev;

import com.str.backend.domain.ZahtjevStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ZahtjevRepository extends JpaRepository<ZahtjevEntity, UUID> {

    Optional<ZahtjevEntity> findByUrZahtjeva(String urZahtjeva);

    boolean existsByUrZahtjeva(String urZahtjeva);

    List<ZahtjevEntity> findByStatus(ZahtjevStatus status);

    List<ZahtjevEntity> findByIdIznajmljivaca(UUID idIznajmljivaca);
}
