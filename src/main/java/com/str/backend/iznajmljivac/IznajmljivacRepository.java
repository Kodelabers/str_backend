package com.str.backend.iznajmljivac;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IznajmljivacRepository extends JpaRepository<IznajmljivacEntity, UUID> {
}
