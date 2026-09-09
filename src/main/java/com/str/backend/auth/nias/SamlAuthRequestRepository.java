package com.str.backend.auth.nias;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface SamlAuthRequestRepository extends JpaRepository<SamlAuthRequestEntity, String> {

    /** Čišćenje zahtjeva na koje odgovor nikad nije stigao (korisnik odustane na NIAS-u). */
    @Modifying
    @Transactional
    @Query("DELETE FROM SamlAuthRequestEntity r WHERE r.createdAt < :threshold")
    int deleteOlderThan(@Param("threshold") Instant threshold);
}
