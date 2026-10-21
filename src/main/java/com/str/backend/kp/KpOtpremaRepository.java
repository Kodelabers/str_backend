package com.str.backend.kp;

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

public interface KpOtpremaRepository extends JpaRepository<KpOtpremaEntity, UUID> {

    @Transactional(readOnly = true)
    Optional<KpOtpremaEntity> findByPismenoId(UUID pismenoId);

    /**
     * Neposlane otpreme spremne za pokušaj. Pismeno mora biti urudžbirano s dokumentom
     * ({@code SYNCED}) — prije toga akt nema urudžbeni broj i ne šalje se.
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
            SELECT o.id FROM KpOtpremaEntity o, EgopPismenoEntity p
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
                                   @Param("neposlani") Collection<KpOtpremaEntity.Status> neposlani,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("notAfter") Instant notAfter,
                                   @Param("notBefore") Instant notBefore,
                                   @Param("now") Instant now);

    /**
     * Poslane poruke s pravnim učinkom čija aktivnost u KP-u još nije konačna (ni potvrđen ni
     * odbijen primitak), najprije one koje se najdulje nisu provjeravale. Za poruke bez pravnog
     * učinka KP aktivnost ne vraća (A005/C005), pa se ni ne pita.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT o.id FROM KpOtpremaEntity o
            WHERE o.status = :sent
              AND o.pravniUcinak = true
              AND o.sentAt > :notBefore
              AND (o.kpStatusAktivnosti IS NULL OR o.kpStatusAktivnosti NOT IN :konacni)
            ORDER BY o.kpStatusCheckedAt ASC NULLS FIRST, o.sentAt ASC
            """)
    List<UUID> findStatusCandidates(@Param("sent") KpOtpremaEntity.Status sent,
                                    @Param("konacni") Collection<Integer> konacni,
                                    @Param("notBefore") Instant notBefore,
                                    Pageable page);
}
