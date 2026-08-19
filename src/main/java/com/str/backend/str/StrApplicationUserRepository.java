package com.str.backend.str;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Read-only pristup externo vlasništvom {@code str.application_user}. Jedina namjena je
 * autorizacija: dohvat internog korisnika po {@code username} (koji je jednak OIB-u za
 * interne fizičke osobe).
 */
@Transactional(readOnly = true)
public interface StrApplicationUserRepository extends Repository<StrApplicationUserEntity, Long> {

    Optional<StrApplicationUserEntity> findFirstByUsernameAndActiveTrue(String username);
}
