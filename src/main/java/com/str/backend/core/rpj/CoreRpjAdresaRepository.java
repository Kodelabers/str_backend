package com.str.backend.core.rpj;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public interface CoreRpjAdresaRepository extends JpaRepository<CoreRpjAdresaEntity, Long> {

    Optional<CoreRpjAdresaEntity> findFirstByZupanijaAndGradAndUlicaAndKucniBroj(
            String zupanija, String grad, String ulica, String kucniBroj);
}
