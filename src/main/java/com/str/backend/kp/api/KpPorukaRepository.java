package com.str.backend.kp.api;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KpPorukaRepository extends JpaRepository<KpPorukaEntity, UUID> {

    /** Redak zaključan do kraja transakcije — dva slanja iste poruke ne zauzmu je istodobno. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM KpPorukaEntity e WHERE e.idPoruke = :id")
    Optional<KpPorukaEntity> lockById(@Param("id") UUID id);

    List<KpPorukaEntity> findByPismenoIdOrderByDio(UUID pismenoId);

    /** Ima li igdje poruka koja se smije poslati — jeftina provjera prije razrješavanja županije. */
    @Query("SELECT COUNT(e) > 0 FROM KpPorukaEntity e "
            + "WHERE e.status = com.str.backend.kp.api.KpPorukaEntity.Status.PENDING AND e.nextAttemptAt <= :now")
    boolean postojeSlobodne(@Param("now") Instant now);

    /** Slobodne poruke županije, najstarije prve. */
    @Query("SELECT e.idPoruke FROM KpPorukaEntity e "
            + "WHERE e.status = com.str.backend.kp.api.KpPorukaEntity.Status.PENDING AND e.nextAttemptAt <= :now "
            + "AND e.izdavateljZuRb = :zuRb ORDER BY e.createdAt, e.dio")
    List<UUID> slobodneZupanije(@Param("zuRb") int zuRb, @Param("now") Instant now, Pageable pageable);
}
