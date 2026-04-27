package com.str.backend.zastupnik;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ZastupnikPravneOsobeRepository extends JpaRepository<ZastupnikPravneOsobeEntity, UUID> {
    List<ZastupnikPravneOsobeEntity> findByIdIznajmljivaca(UUID idIznajmljivaca);
}
