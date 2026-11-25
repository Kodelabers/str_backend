package com.str.backend.objection;

import com.str.backend.domain.RnStatus;
import org.springframework.data.domain.Page;
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

public interface SuspensionObjectionRepository extends JpaRepository<SuspensionObjectionEntity, UUID> {

    /** Postoji li uz RB prigovor u zadanom statusu — neobrađen smije biti najviše jedan. */
    @Transactional(readOnly = true)
    boolean existsByRnAndStatus(String rn, SuspensionObjectionStatus status);

    /** Postoji li uz RB prigovor u zadanom statusu predan prije {@code before}, bez obzira na prijedlog. */
    @Transactional(readOnly = true)
    boolean existsByRnAndStatusAndSubmittedAtBefore(String rn, SuspensionObjectionStatus status, Instant before);

    /**
     * Postoji li neobrađen ({@code SUBMITTED}) prigovor na <b>ovaj</b> prijedlog suspenzije, predan prije
     * {@code before} — za {@code SuspensionDeadlineJob}: prigovor predan do kraja dana roka zadržava
     * suspenziju. Prigovor pripada prijedlogu po {@code rn_log_id}; stariji bez njega po vremenu predaje
     * (nakon prijedloga). Zaostali prigovor na raniji prijedlog ne smije zauvijek zadržati novi.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT COUNT(o) > 0 FROM SuspensionObjectionEntity o
            WHERE o.rn = :rn AND o.status = com.str.backend.objection.SuspensionObjectionStatus.SUBMITTED
              AND o.submittedAt < :before
              AND (o.rnLogId = :prijedlogId OR (o.rnLogId IS NULL AND o.submittedAt >= :prijedlogAt))
            """)
    boolean existsSubmittedForProposal(@Param("rn") String rn, @Param("prijedlogId") UUID prijedlogId,
                                       @Param("prijedlogAt") Instant prijedlogAt, @Param("before") Instant before);

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
        UUID getRnLogId();
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
                   o.lessorEmail AS lessorEmail, o.rnStatus AS rnStatus, o.rnLogId AS rnLogId, o.fileName AS fileName,
                   o.contentType AS contentType, o.fileSize AS fileSize, o.status AS status,
                   o.submittedAt AS submittedAt, o.resolvedBy AS resolvedBy, o.resolvedAt AS resolvedAt,
                   o.resolutionNote AS resolutionNote
            FROM SuspensionObjectionEntity o
            WHERE o.rn = :rn
            ORDER BY o.submittedAt DESC
            """)
    List<ObjectionSummary> findSummariesByRn(@Param("rn") String rn);

    // --- Popis očitovanja i prigovora preko svih RB-ova (MINTS 17.11., točka 1). Dijelovi upita su
    // konstante, pa lista, brojanje i kartice ne mogu različito shvatiti „neriješeno".

    /**
     * Podnesak, broj, objekt i nositelj broja (kao {@code RnRepository#RN_FROM}), te prijelaz na koji podnesak
     * odgovara ({@code g}) — njegov ručni akt znači županijski postupak.
     */
    String ADMIN_FROM =
            " FROM SuspensionObjectionEntity o" +
            " JOIN RnEntity r ON r.rn = o.rn" +
            " JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId" +
            " LEFT JOIN LessorEntity l ON l.lessorId =" +
            "     (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)" +
            " LEFT JOIN RegistrationNumberLogEntity g ON g.logId = o.rnLogId";

    /**
     * Podnesak je urudžbiran: uz njega postoji pismeno s URBROJ-em ({@code act_ref} = {@code PRIGOVOR:<id>},
     * {@code EgopPismenoEntity#actRefPodneska}) — ručno u županijskom postupku ili vraćeno iz eGOP-a.
     */
    String URUDZBIRAN =
            "EXISTS (SELECT 1 FROM EgopPismenoEntity p WHERE p.rn = o.rn AND p.urBroj IS NOT NULL" +
            " AND p.actRef = CONCAT('PRIGOVOR:', CAST(o.objectionId AS string)))";

    /** Županijski postupak: prijelaz na koji podnesak odgovara nosi ručni akt (KLASA i URBROJ županije). */
    String COUNTY_PROCEDURE = "g.aktUrBroj IS NOT NULL";

    /**
     * Neriješen podnesak: čeka odluku ({@code SUBMITTED}), ili je u županijskom postupku (ručni akt na prijelazu,
     * {@code RegistrationNumberLogEntity#rucniAkt}) zatvoren bez urudžbe — svako ulazno pismeno mora biti
     * urudžbirano. Isto pravilo kao „u obradi" na detalju RB-a (FE {@code RnPrigovoriPanel}).
     */
    String UNRESOLVED =
            "(o.status = com.str.backend.objection.SuspensionObjectionStatus.SUBMITTED" +
            " OR (" + COUNTY_PROCEDURE +
            "     AND o.status = com.str.backend.objection.SuspensionObjectionStatus.CLOSED" +
            "     AND NOT " + URUDZBIRAN + "))";

    /** Pretraga po slobodnom upisu: broj, objekt, nositelj, OIB ili porezni broj, županija. */
    String ADMIN_HAYSTACK =
            "LOWER(CONCAT(COALESCE(o.rn,''),' ',COALESCE(a.name,''),' ',COALESCE(l.firstName,''),' '," +
            "COALESCE(l.lastName,''),' ',COALESCE(l.legalEntityName,''),' ',COALESCE(l.lessorOib,''),' '," +
            "COALESCE(l.taxNumber,''),' ',COALESCE(a.county,'')))";

    /** Jedan AND-uvjet po tokenu ({@code SearchTokens#slots}); prazni se ne primjenjuju. */
    String ADMIN_TOKENS =
            " AND (CAST(:tok0 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok0 AS string), '%'))" +
            " AND (CAST(:tok1 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok1 AS string), '%'))" +
            " AND (CAST(:tok2 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok2 AS string), '%'))" +
            " AND (CAST(:tok3 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok3 AS string), '%'))" +
            " AND (CAST(:tok4 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok4 AS string), '%'))" +
            " AND (CAST(:tok5 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok5 AS string), '%'))" +
            " AND (CAST(:tok6 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok6 AS string), '%'))" +
            " AND (CAST(:tok7 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok7 AS string), '%'))" +
            " AND (CAST(:tok8 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok8 AS string), '%'))" +
            " AND (CAST(:tok9 AS string) IS NULL OR " + ADMIN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok9 AS string), '%'))";

    /**
     * Filtri popisa. {@code :kindStatuses} je uvijek neprazan (bez filtra vrste sve četiri, {@link ObjectionKind}).
     * Opseg službenika kao u registru: lista je uvijek neprazna, a kad opseg nije ograničen ({@code :scopeMode}
     * NULL), ne koristi se.
     *
     * <p>{@code :submittedBefore} („stariji od X dana") je UVIJEK zadan — bez filtra daleka budućnost
     * ({@code AdminObjectionService}). Nikad {@code :p IS NULL} za {@code Instant}: Hibernate taj null šalje
     * Postgresu bez tipa (za razliku od {@code LocalDate}, koji ide kao {@code date}), pa upit puca s
     * {@code could not determine data type of parameter}. H2 to propušta, pa test to ne bi uhvatio.
     */
    String ADMIN_FILTERS =
            " WHERE o.rnStatus IN :kindStatuses" +
            " AND (CAST(:scopeMode AS string) IS NULL OR a.county IN :scopeCounties)" +
            " AND (CAST(:resolution AS string) IS NULL" +
            "      OR (CAST(:resolution AS string) = 'UNRESOLVED' AND " + UNRESOLVED + ")" +
            "      OR (CAST(:resolution AS string) = 'RESOLVED' AND NOT " + UNRESOLVED + "))" +
            " AND o.submittedAt < :submittedBefore" +
            " AND (CAST(:rb AS string) IS NULL OR LOWER(o.rn) LIKE CONCAT('%', LOWER(CAST(:rb AS string)), '%'))" +
            " AND (CAST(:name AS string) IS NULL OR LOWER(CONCAT(COALESCE(l.firstName,''),' ',COALESCE(l.lastName,'')," +
            "      ' ',COALESCE(l.legalEntityName,''))) LIKE CONCAT('%', LOWER(CAST(:name AS string)), '%'))" +
            " AND (CAST(:oib AS string) IS NULL OR LOWER(CONCAT(COALESCE(l.lessorOib,''),' ',COALESCE(l.taxNumber,'')))" +
            "      LIKE CONCAT('%', LOWER(CAST(:oib AS string)), '%'))";

    /** Popis očitovanja i prigovora preko svih RB-ova, bez sadržaja datoteka — v. {@link AdminObjectionRowDto}. */
    @Transactional(readOnly = true)
    @Query(value = "SELECT new com.str.backend.objection.AdminObjectionRowDto("
            + " o.objectionId, o.rn, o.rnStatus, l.firstName, l.lastName, l.legalEntityName, l.lessorOib,"
            + " l.taxNumber, a.name, a.county, o.submittedAt, o.status,"
            + " CASE WHEN " + COUNTY_PROCEDURE + " THEN true ELSE false END,"
            + " CASE WHEN " + UNRESOLVED + " THEN false ELSE true END, o.resolvedAt)"
            + ADMIN_FROM + ADMIN_FILTERS + ADMIN_TOKENS,
            countQuery = "SELECT COUNT(o)" + ADMIN_FROM + ADMIN_FILTERS + ADMIN_TOKENS)
    Page<AdminObjectionRowDto> searchForAdmin(
            @Param("kindStatuses") Collection<RnStatus> kindStatuses,
            @Param("scopeMode") String scopeMode,
            @Param("scopeCounties") Collection<String> scopeCounties,
            @Param("resolution") String resolution,
            @Param("submittedBefore") Instant submittedBefore,
            @Param("rb") String rb,
            @Param("name") String name,
            @Param("oib") String oib,
            @Param("tok0") String tok0, @Param("tok1") String tok1, @Param("tok2") String tok2,
            @Param("tok3") String tok3, @Param("tok4") String tok4, @Param("tok5") String tok5,
            @Param("tok6") String tok6, @Param("tok7") String tok7, @Param("tok8") String tok8,
            @Param("tok9") String tok9,
            Pageable pageable);

    /**
     * Neriješeni podnesci u opsegu službenika, predani u {@code [submittedFrom, submittedBefore)}. Kartice popisa
     * ({@link AdminObjectionStatsDto}). Obje granice su uvijek zadane (bez granice krajnja vrijednost), iz istog
     * razloga kao {@code :submittedBefore} u {@link #ADMIN_FILTERS}.
     */
    @Transactional(readOnly = true)
    @Query("SELECT COUNT(o)" + ADMIN_FROM
            + " WHERE " + UNRESOLVED
            + " AND (CAST(:scopeMode AS string) IS NULL OR a.county IN :scopeCounties)"
            + " AND o.submittedAt >= :submittedFrom"
            + " AND o.submittedAt < :submittedBefore")
    long countUnresolvedForAdmin(@Param("scopeMode") String scopeMode,
                                 @Param("scopeCounties") Collection<String> scopeCounties,
                                 @Param("submittedFrom") Instant submittedFrom,
                                 @Param("submittedBefore") Instant submittedBefore);

    /**
     * Riješeni podnesci u opsegu službenika, bez vremenskog ograničenja — kartica „Riješeno"
     * ({@link AdminObjectionStatsDto#resolved}). Isti uvjet kao filtar {@code RESOLVED} popisa, pa se broj slaže
     * s popisom filtriranim na riješene.
     */
    @Transactional(readOnly = true)
    @Query("SELECT COUNT(o)" + ADMIN_FROM
            + " WHERE NOT " + UNRESOLVED
            + " AND (CAST(:scopeMode AS string) IS NULL OR a.county IN :scopeCounties)")
    long countResolvedForAdmin(@Param("scopeMode") String scopeMode,
                               @Param("scopeCounties") Collection<String> scopeCounties);
}
