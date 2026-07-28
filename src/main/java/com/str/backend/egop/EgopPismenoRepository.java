package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EgopPismenoRepository extends JpaRepository<EgopPismenoEntity, UUID> {

    @Transactional(readOnly = true)
    List<EgopPismenoEntity> findBySubmissionId(UUID submissionId);

    /** Akti životnog ciklusa vezani uz RB (registracijski akti imaju {@code rn} prazan). */
    @Transactional(readOnly = true)
    List<EgopPismenoEntity> findByRnOrderByCreatedAtAsc(String rn);

    /**
     * Dedupe ključ urudžbiranja — poklapa se s unique constraintom
     * {@code uq_egop_pismeno_submission_vrsta_act} (changeset 054). {@code actRef} razrješava
     * ponovljive akte: dvije suspenzije istog RB-a su dva različita akta iste vrste.
     */
    @Transactional(readOnly = true)
    Optional<EgopPismenoEntity> findBySubmissionIdAndVrstaPismenaNazivAndActRef(
            UUID submissionId, String vrstaPismenaNaziv, String actRef);

    /**
     * Nedovršeni akti spremni za ponovni pokušaj. Registracijski akti se <b>izostavljaju</b> —
     * njih vodi {@code EgopRetryJob} preko submissiona, jer im se PDF renderira tek iz
     * urudžbenog broja i ne može se poslati unaprijed.
     *
     * <p>Zadnji uvjet razlikuje dva slučaja i time sprječava da cron otme akt inline slanju
     * koje je još u tijeku:
     * <ul>
     *   <li>{@code nextAttemptAt} postavljen → prethodni pokušaj je pao, poštuje se backoff;</li>
     *   <li>{@code nextAttemptAt} prazan → akt još nije ni pokušan, čeka se {@code notAfter}
     *       (grace) da inline slanje dobije priliku dovršiti.</li>
     * </ul>
     *
     * @param bezSifre nazivi vrsta pismena koje eGOP šifrarnik nema — zapisuju se i prikazuju
     *                 stranci, ali se ne šalju ({@link EgopAktiBezSifre}). Bez ovog uvjeta cron
     *                 bi urudžbirao upravo ono što je listener namjerno preskočio. Nikad prazno:
     *                 JPQL {@code NOT IN ()} je sintaktička greška.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT p.id FROM EgopPismenoEntity p
            WHERE p.status <> :synced
              AND p.actRef <> :registracija
              AND p.vrstaPismenaNaziv NOT IN :bezSifre
              AND p.syncAttempts < :maxAttempts
              AND p.createdAt > :notBefore
              AND ((p.nextAttemptAt IS NOT NULL AND p.nextAttemptAt <= :now)
                   OR (p.nextAttemptAt IS NULL AND p.createdAt < :notAfter))
            ORDER BY p.createdAt ASC
            """)
    List<UUID> findRetryCandidates(@Param("synced") EgopSyncStatus synced,
                                   @Param("registracija") String registracija,
                                   @Param("bezSifre") Collection<String> bezSifre,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("notAfter") Instant notAfter,
                                   @Param("notBefore") Instant notBefore,
                                   @Param("now") Instant now);
}
