package com.str.backend.objection;

import com.str.backend.domain.RnStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SuspensionObjectionRepository extends JpaRepository<SuspensionObjectionEntity, UUID> {

    /** Postoji li uz RB prigovor u zadanom statusu — neobrađen smije biti najviše jedan. */
    @Transactional(readOnly = true)
    boolean existsByRnAndStatus(String rn, SuspensionObjectionStatus status);

    /** Prigovori uz RB u zadanom statusu — za zatvaranje kad RB napusti prijedlog/suspenziju. */
    List<SuspensionObjectionEntity> findByRnAndStatus(String rn, SuspensionObjectionStatus status);

    /** Prigovor uz zadani RB — tuđi id pod ovim RB-om je 404, ne prigovor drugog RB-a. */
    Optional<SuspensionObjectionEntity> findByObjectionIdAndRn(UUID objectionId, String rn);

    /** Sažetak prigovora bez sadržaja datoteke — povijest na detalju RB-a ne treba BLOB. */
    interface ObjectionSummary {
        UUID getObjectionId();
        String getRn();
        String getLessorOib();
        UUID getLessorId();
        String getLessorEmail();
        RnStatus getRnStatus();
        String getFileName();
        String getContentType();
        long getFileSize();
        SuspensionObjectionStatus getStatus();
        Instant getSubmittedAt();
        String getResolvedBy();
        Instant getResolvedAt();
        String getResolutionNote();
    }

    /** Povijest prigovora uz RB, najnoviji prvi, bez {@code file_content}. */
    @Transactional(readOnly = true)
    @Query("""
            SELECT o.objectionId AS objectionId, o.rn AS rn, o.lessorOib AS lessorOib, o.lessorId AS lessorId,
                   o.lessorEmail AS lessorEmail, o.rnStatus AS rnStatus, o.fileName AS fileName,
                   o.contentType AS contentType, o.fileSize AS fileSize, o.status AS status,
                   o.submittedAt AS submittedAt, o.resolvedBy AS resolvedBy, o.resolvedAt AS resolvedAt,
                   o.resolutionNote AS resolutionNote
            FROM SuspensionObjectionEntity o
            WHERE o.rn = :rn
            ORDER BY o.submittedAt DESC
            """)
    List<ObjectionSummary> findSummariesByRn(@Param("rn") String rn);
}
