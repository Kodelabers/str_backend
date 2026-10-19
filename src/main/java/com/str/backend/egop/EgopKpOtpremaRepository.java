package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EgopKpOtpremaRepository extends JpaRepository<EgopKpOtpremaEntity, UUID> {

    @Transactional(readOnly = true)
    Optional<EgopKpOtpremaEntity> findByPismenoId(UUID pismenoId);

    /**
     * Neposlane otpreme spremne za pokušaj. Pismeno mora biti urudžbirano s dokumentom
     * ({@code SYNCED}) — prije toga eGOP nema što otpremiti.
     *
     * <p>Vremena se mjere od trenutka kad je pismeno postalo {@code SYNCED} ({@code p.updatedAt}),
     * ne od nastanka otpreme: akt čija vrsta još nema eGOP šifru urudžbira se tek kad šifra stigne,
     * možda tjednima kasnije, i tek tada mu počinje teći otprema.
     * <ul>
     *   <li>{@code nextAttemptAt} postavljen → prethodni pokušaj je pao, poštuje se backoff;</li>
     *   <li>{@code nextAttemptAt} prazan → još nije pokušano, čeka se {@code notAfter} (grace) da
     *       inline slanje nakon urudžbe dobije priliku.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT o.id FROM EgopKpOtpremaEntity o, EgopPismenoEntity p
            WHERE p.id = o.pismenoId
              AND p.status = :synced
              AND o.status IN :neposlani
              AND o.attempts < :maxAttempts
              AND p.updatedAt > :notBefore
              AND ((o.nextAttemptAt IS NOT NULL AND o.nextAttemptAt <= :now)
                   OR (o.nextAttemptAt IS NULL AND p.updatedAt < :notAfter))
            ORDER BY o.createdAt ASC
            """)
    List<UUID> findRetryCandidates(@Param("synced") EgopSyncStatus synced,
                                   @Param("neposlani") Collection<EgopKpOtpremaEntity.Status> neposlani,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("notAfter") Instant notAfter,
                                   @Param("notBefore") Instant notBefore,
                                   @Param("now") Instant now);

    /**
     * Poslane poruke čiji status u KP-u još nije konačan (ni potvrđen ni odbijen primitak),
     * najprije one koje se najdulje nisu provjeravale.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT o.id FROM EgopKpOtpremaEntity o
            WHERE o.status = :sent
              AND o.sentAt > :notBefore
              AND (o.kpStatusPoruke IS NULL OR o.kpStatusPoruke NOT IN :konacni)
            ORDER BY o.kpStatusCheckedAt ASC NULLS FIRST, o.sentAt ASC
            """)
    List<UUID> findStatusCandidates(@Param("sent") EgopKpOtpremaEntity.Status sent,
                                    @Param("konacni") Collection<Integer> konacni,
                                    @Param("notBefore") Instant notBefore,
                                    Pageable page);
}
