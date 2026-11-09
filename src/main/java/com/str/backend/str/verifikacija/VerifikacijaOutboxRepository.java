package com.str.backend.str.verifikacija;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerifikacijaOutboxRepository extends JpaRepository<VerifikacijaOutboxEntity, UUID> {

    Optional<VerifikacijaOutboxEntity> findByRn(String rn);

    /**
     * Redak zaključan do kraja transakcije ({@code SELECT … FOR UPDATE}): inline pokušaj nakon
     * commita i retry job ne mogu isti RB obraditi istodobno — drugi čeka pa vidi da redak više
     * nije {@code PENDING}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM VerifikacijaOutboxEntity e WHERE e.rn = :rn")
    Optional<VerifikacijaOutboxEntity> lockByRn(@Param("rn") String rn);

    /** Dospjeli zapisi zadanih vrsta, najstariji rok prvi — {@code pageable} ograničava veličinu serije. */
    List<VerifikacijaOutboxEntity> findByStatusAndVrstaInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            VerifikacijaOutboxEntity.Status status, Collection<VerifikacijaOutboxEntity.Vrsta> vrste, Instant now,
            Pageable pageable);
}
