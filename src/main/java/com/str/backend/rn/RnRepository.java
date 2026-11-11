package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.dto.RnDetailExtras;
import com.str.backend.rn.dto.RnAccommodationStatsDto;
import com.str.backend.rn.dto.RnPublicView;
import com.str.backend.rn.dto.RnSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RnRepository extends JpaRepository<RnEntity, String> {

    boolean existsByRn(String rn);

    /** Ulaz za izračun roka ponovnog zahtjeva na popisu RB-ova — v. {@link #findReissueCandidates}. */
    interface ReissueCandidateRow {
        String getRn();
        RnStatus getStatus();
        LocalDate getValidTo();
        String getFacilityId();
        /** Aktivno (predano/potvrđeno) rješenje uz RB ima {@code facility_id} — objekt je u eTurizmu. */
        Boolean getDecisionInETurizam();
        Boolean getUsed();
    }

    /**
     * Opozvani i povučeni RB-ovi iz zadanog popisa s onim što pravilo ponovnog zahtjeva (T15)
     * traži: status, dan opoziva odnosno povlačenja, eTurizam objekt smještaja ili rješenja i je
     * li RB već iskorišten. Jedan upit za cijeli popis „Mojih registracijskih brojeva"; pravilo
     * samo primjenjuje {@code ReissueService}.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT r.rn AS rn, r.status AS status, r.validTo AS validTo, a.facilityId AS facilityId,
                   CASE WHEN EXISTS (SELECT 1 FROM CategorizationDecisionEntity d WHERE d.rn = r.rn
                                     AND d.status IN (com.str.backend.categorization.CategorizationDecisionStatus.SUBMITTED,
                                                      com.str.backend.categorization.CategorizationDecisionStatus.VERIFIED)
                                     AND d.facilityId IS NOT NULL AND TRIM(d.facilityId) <> '')
                        THEN true ELSE false END AS decisionInETurizam,
                   CASE WHEN EXISTS (SELECT 1 FROM AccommodationEntity n WHERE n.previousRn = r.rn)
                        THEN true ELSE false END AS used
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE r.rn IN :rns AND r.status IN (com.str.backend.domain.RnStatus.REVOKED,
                                                com.str.backend.domain.RnStatus.WITHDRAWN)
            """)
    List<ReissueCandidateRow> findReissueCandidates(@Param("rns") Collection<String> rns);

    List<RnEntity> findByAccommodationId(UUID accommodationId);

    List<RnEntity> findBySubmissionId(UUID submissionId);

    Optional<RnEntity> findTopByAccommodationIdAndStatusOrderByCreatedAtDesc(UUID accommodationId, RnStatus status);

    List<RnEntity> findByStatusInOrderByUpdatedAtDesc(List<RnStatus> statuses);

    /** Kao {@link #findByStatusInOrderByUpdatedAtDesc}, samo za smještaje u zadanim županijama (opseg službenika). */
    @Transactional(readOnly = true)
    @Query("SELECT r FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId"
            + " WHERE r.status IN :statuses AND a.county IN :counties ORDER BY r.updatedAt DESC")
    List<RnEntity> findByStatusInAndCountyIn(@Param("statuses") List<RnStatus> statuses,
                                             @Param("counties") Collection<String> counties);

    /** Županija smještaja RB-a kako je zapisana ({@code accommodation.county}), za provjeru opsega službenika. */
    @Transactional(readOnly = true)
    @Query("SELECT a.county FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId"
            + " WHERE r.rn = :rn")
    Optional<String> findCountyByRn(@Param("rn") String rn);

    /**
     * RB za promjenu statusa, zaključan do kraja transakcije ({@code SELECT … FOR UPDATE}).
     * Svaki prijelaz statusa kreće odavde: bez zaključavanja bi istodobni opoziv iznajmljivača i
     * povlačenje službenika oba prošla {@code canTransitionTo} nad istim {@code ACTIVE}, oba
     * upisala zapis i akt, a status bi ostao onaj koji je zadnji commitao. Ovako drugi čeka prvog,
     * čita njegov novi status i dobiva 409.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RnEntity r WHERE r.rn = :rn")
    Optional<RnEntity> findForUpdate(@Param("rn") String rn);

    /** RB-ovi u prijedlogu suspenzije kojima je rok za očitovanje istekao — {@code SuspensionDeadlineJob}. */
    @Query("""
            SELECT r.rn FROM RnEntity r
            WHERE r.status = com.str.backend.domain.RnStatus.SUSPENSION_PROPOSED
              AND r.suspensionDeadline < :date
            """)
    List<String> findRnsWithSuspensionDeadlineBefore(@Param("date") LocalDate date);

    /** RB-ovi u prijedlogu povlačenja kojima je rok za očitovanje istekao — {@code WithdrawalDeadlineJob}. */
    @Query("""
            SELECT r.rn FROM RnEntity r
            WHERE r.status = com.str.backend.domain.RnStatus.WITHDRAWAL_PROPOSED
              AND r.withdrawalDeadline < :date
            """)
    List<String> findRnsWithWithdrawalDeadlineBefore(@Param("date") LocalDate date);

    /**
     * RB-ovi izdani ponovnim zahtjevom (T15) na temelju zadanog broja ({@code accommodation.previous_rn}).
     * {@code RnService#reinstate} po njima prati lanac: uvaženi prigovor ne smije vratiti povučen broj
     * ako je za isti objekt u međuvremenu izdan novi.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT r FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE a.previousRn = :rn
            """)
    List<RnEntity> findReissuesOf(@Param("rn") String rn);

    /** STR-1.3 retencija: konačni RB-ovi kojima je valid_to (dan opoziva/povlačenja) stariji od praga. */
    List<RnEntity> findByStatusInAndValidToBefore(Collection<RnStatus> statuses, java.time.LocalDate cutoff);

    interface FacilityRnRow {
        String getFacilityId();
        String getRn();
    }

    /**
     * RB-ovi koje je STR izdao za eTurizam objekte iz predanog popisa.
     *
     * <p>Popis objekata na NIAS dashboardu primarno čita RB iz {@code str.facility.registration_number},
     * ali je taj write-back best-effort po dizajnu ({@code FacilityRegistrationNumberWriteBack}
     * ne retryja i ne obara izdavanje). Bez ovog upita objekt s izdanim RB-om izgledao bi kao da
     * ga nema svaki put kad je upis u tuđu shemu pao.
     *
     * <p>Uzimaju se samo još stojeći RB-ovi; {@code REVOKED} i {@code WITHDRAWN} su konačni, a
     * {@code WITHDRAWAL_PROPOSED} je još broj objekta (rok za očitovanje teče).
     * Statusi se nabrajaju eksplicitno da novi status ne bi tiho ušao u popis.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT a.facilityId AS facilityId, r.rn AS rn
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE a.facilityId IN :facilityIds
              AND r.status IN (com.str.backend.domain.RnStatus.ACTIVE,
                               com.str.backend.domain.RnStatus.SUSPENSION_PROPOSED,
                               com.str.backend.domain.RnStatus.SUSPENDED,
                               com.str.backend.domain.RnStatus.WITHDRAWAL_PROPOSED)
            ORDER BY r.issueDate
            """)
    List<FacilityRnRow> findRnsByFacilityIds(@Param("facilityIds") List<String> facilityIds);

    /** RB-ovi eTurizam objekta u zadanom statusu — {@code RnService#issue} zatvara prigovore na povučene (T12). */
    @Transactional(readOnly = true)
    @Query("""
            SELECT r.rn FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE a.facilityId = :facilityId AND r.status = :status
            """)
    List<String> findRnsOfFacilityInStatus(@Param("facilityId") String facilityId, @Param("status") RnStatus status);

    /**
     * Koji su od zadanih RB-ova konačni (opozvani ili povučeni). eTurizam
     * ({@code str.facility.registration_number}) može još držati broj koji je u međuvremenu
     * opozvan ili povučen — takav nije broj objekta i ne smije spriječiti novi zahtjev. Broj kojeg STR ne zna (ručno upisan u eTurizmu) ovdje ne izlazi.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT r.rn FROM RnEntity r
            WHERE r.rn IN :rns AND r.status IN (com.str.backend.domain.RnStatus.REVOKED,
                                                com.str.backend.domain.RnStatus.WITHDRAWN)
            """)
    List<String> findWithdrawnRns(@Param("rns") Collection<String> rns);

    /**
     * eTurizam objekt uz koji je RB izdan; prazno kad RB nije došao kroz tuStart handoff.
     * Najviše jedan red: {@code rn} je ključ RB-a, a {@code accommodation_id} ključ smještaja.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT a.facilityId
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE r.rn = :rn AND a.facilityId IS NOT NULL
            """)
    Optional<String> findFacilityIdByRn(@Param("rn") String rn);

    /** STR statistics: counts of RNs grouped by accommodation county + RN status. */
    @Transactional(readOnly = true)
    @Query("""
            SELECT a.county AS county, r.status AS status, r.withdrawalFromSuspended AS withdrawalFromSuspended,
                   COUNT(r) AS count
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            GROUP BY a.county, r.status, r.withdrawalFromSuspended
            """)
    List<CountyStatusCount> countByCountyAndStatus();

    /** STR statistics: same as above but only RNs issued within the given date range (inclusive). */
    @Transactional(readOnly = true)
    @Query("""
            SELECT a.county AS county, r.status AS status, r.withdrawalFromSuspended AS withdrawalFromSuspended,
                   COUNT(r) AS count
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE r.issueDate BETWEEN :from AND :to
            GROUP BY a.county, r.status, r.withdrawalFromSuspended
            """)
    List<CountyStatusCount> countByCountyAndStatusBetween(@Param("from") java.time.LocalDate from,
                                                          @Param("to") java.time.LocalDate to);

    /** STR statistics: distinct accommodations per county that had at least one RN issued in [from, to].
     *  Drives the "accommodations" column + totalObjects KPI when a date filter is active, so empty
     *  periods report empty values just as they would with real data. */
    @Transactional(readOnly = true)
    @Query("""
            SELECT a.county AS county, COUNT(DISTINCT a.accommodationId) AS count
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE r.issueDate BETWEEN :from AND :to
            GROUP BY a.county
            """)
    List<CountyCount> countDistinctAccommodationsByCountyBetween(@Param("from") java.time.LocalDate from,
                                                                 @Param("to") java.time.LocalDate to);

    interface CountyStatusCount {
        String getCounty();
        RnStatus getStatus();
        /** Prijedlog povlačenja pokrenut iz suspenzije (T12) — statistika ga broji kao suspendiran. */
        Boolean getWithdrawalFromSuspended();
        long getCount();
    }

    interface CountyCount {
        String getCounty();
        long getCount();
    }

    /*
     * Rješenje o kategorizaciji uz RB — jedno pravilo za „Moje registracijske brojeve" i registar.
     * Mjerodavno je potvrđeno, pa predano, pa odbijeno. Aktivno (predano ili potvrđeno) može biti
     * najviše jedno po RB-u (changeset 126), pa redoslijed daje jednoznačan status bez oslanjanja
     * na vrijeme uploada. Rješenje se traži samo uz RB novog objekta (bez eTurizam facilityId)
     * koji nije opozvan ni povučen — isto pravilo kao
     * CategorizationDecisionService.UPLOAD_ALLOWED_RN_STATUSES.
     */

    String DECISION_VERIFIED_EXISTS =
            "EXISTS (SELECT 1 FROM CategorizationDecisionEntity d WHERE d.rn = r.rn" +
            " AND d.status = com.str.backend.categorization.CategorizationDecisionStatus.VERIFIED)";

    /**
     * Prihvaćeno rješenje čiji je objekt upisan u TuRegistar (T19, „Potvrda upisa objekta"): uz
     * rješenje je zapisana jedinica TuRegistra. Podskup {@link #DECISION_VERIFIED_EXISTS}.
     */
    String DECISION_REGISTERED_EXISTS =
            "EXISTS (SELECT 1 FROM CategorizationDecisionEntity d WHERE d.rn = r.rn" +
            " AND d.status = com.str.backend.categorization.CategorizationDecisionStatus.VERIFIED" +
            " AND d.facilityId IS NOT NULL AND TRIM(d.facilityId) <> '')";

    /** {@code boolean}: je li objekt prihvaćenog rješenja upisan u TuRegistar („Upisano"). */
    String CATEGORIZATION_REGISTERED =
            "CASE WHEN " + DECISION_REGISTERED_EXISTS + " THEN true ELSE false END";

    String DECISION_SUBMITTED_EXISTS =
            "EXISTS (SELECT 1 FROM CategorizationDecisionEntity d WHERE d.rn = r.rn" +
            " AND d.status = com.str.backend.categorization.CategorizationDecisionStatus.SUBMITTED)";

    String DECISION_REJECTED_EXISTS =
            "EXISTS (SELECT 1 FROM CategorizationDecisionEntity d WHERE d.rn = r.rn" +
            " AND d.status = com.str.backend.categorization.CategorizationDecisionStatus.REJECTED)";

    String DECISION_REQUIRED_CONDITION =
            "(a.facilityId IS NULL OR TRIM(a.facilityId) = '')" +
            " AND r.status NOT IN (com.str.backend.domain.RnStatus.REVOKED," +
            " com.str.backend.domain.RnStatus.WITHDRAWN)";

    /** {@code boolean}: traži li RB rješenje o kategorizaciji. */
    String CATEGORIZATION_REQUIRED =
            "CASE WHEN " + DECISION_REQUIRED_CONDITION + " THEN true ELSE false END";

    /** {@code CategorizationDecisionStatus}: status mjerodavnog rješenja ili {@code NULL} kad ga nema. */
    String CATEGORIZATION_STATUS =
            "CASE" +
            " WHEN " + DECISION_VERIFIED_EXISTS +
            " THEN com.str.backend.categorization.CategorizationDecisionStatus.VERIFIED" +
            " WHEN " + DECISION_SUBMITTED_EXISTS +
            " THEN com.str.backend.categorization.CategorizationDecisionStatus.SUBMITTED" +
            " WHEN " + DECISION_REJECTED_EXISTS +
            " THEN com.str.backend.categorization.CategorizationDecisionStatus.REJECTED" +
            " ELSE NULL END";

    /**
     * {@code SuspensionObjectionStatus}: status zadnjeg prigovora u postupku koji je u tijeku, ili
     * {@code NULL} kad ga nema. Neobrađen je najviše jedan (changeset 145), a dva prigovora istog RB-a ne
     * dijele trenutak predaje.
     *
     * <p>Postupak je vezan uz zadnji ulazak u njegov prijedlog (T12): u {@code SUSPENSION_PROPOSED} i
     * {@code SUSPENDED} to je zadnji prijelaz u {@code SUSPENSION_PROPOSED}, a u {@code WITHDRAWAL_PROPOSED}
     * i {@code WITHDRAWN} zadnji prijelaz u {@code WITHDRAWAL_PROPOSED} — prigovor na prijedlog vrijedi i
     * nakon isteka roka, jer tada ostaje otvoren ({@code SuspensionObjectionLifecycleListener}). Stariji
     * zapisi bez prijedloga (jednofazna suspenzija, izravno povlačenje prije T12) mjere se od ulaska u
     * mjeru. Prigovor iz ranijeg postupka se ne prikazuje, a u {@code ACTIVE} i {@code REVOKED} statusa nema.
     */
    String OBJECTION_STATUS =
            "(SELECT o.status FROM SuspensionObjectionEntity o WHERE o.rn = r.rn" +
            " AND o.submittedAt = (SELECT MAX(o2.submittedAt) FROM SuspensionObjectionEntity o2" +
            " WHERE o2.rn = r.rn)" +
            " AND o.submittedAt >= (SELECT MAX(l.occurredAt) FROM RegistrationNumberLogEntity l WHERE l.rn = r.rn" +
            "   AND ((r.status IN (com.str.backend.domain.RnStatus.SUSPENSION_PROPOSED," +
            "                      com.str.backend.domain.RnStatus.SUSPENDED)" +
            "         AND (l.toStatus = 'SUSPENSION_PROPOSED'" +
            "              OR (l.toStatus = 'SUSPENDED' AND NOT EXISTS (SELECT 1 FROM RegistrationNumberLogEntity p" +
            "                  WHERE p.rn = r.rn AND p.toStatus = 'SUSPENSION_PROPOSED'))))" +
            "     OR (r.status IN (com.str.backend.domain.RnStatus.WITHDRAWAL_PROPOSED," +
            "                      com.str.backend.domain.RnStatus.WITHDRAWN)" +
            "         AND (l.toStatus = 'WITHDRAWAL_PROPOSED'" +
            "              OR (l.toStatus = 'WITHDRAWN' AND NOT EXISTS (SELECT 1 FROM RegistrationNumberLogEntity w" +
            "                  WHERE w.rn = r.rn AND w.toStatus = 'WITHDRAWAL_PROPOSED')))))))";

    /**
     * Ono što stupac prikazuje, kao ime {@link RegistryDecisionFilter} — filtar registra. Isto kao
     * FE {@code shownDecisionStatus}: status rješenja kad postoji, inače NONE ili NOT_REQUIRED.
     */
    String CATEGORIZATION_SHOWN =
            "CASE" +
            " WHEN " + DECISION_REGISTERED_EXISTS + " THEN 'REGISTERED'" +
            " WHEN " + DECISION_VERIFIED_EXISTS + " THEN 'VERIFIED'" +
            " WHEN " + DECISION_SUBMITTED_EXISTS + " THEN 'SUBMITTED'" +
            " WHEN " + DECISION_REJECTED_EXISTS + " THEN 'REJECTED'" +
            " WHEN " + DECISION_REQUIRED_CONDITION + " THEN 'NONE'" +
            " ELSE 'NOT_REQUIRED' END";

    // --- Registry megasearch (token-AND) building blocks. Shared between data + count queries so
    // the WHERE clause cannot drift between them. @Query accepts constant string concatenation.

    /** Per-record searchable haystack: all fields COALESCE'd (a NULL field must not null the whole CONCAT). */
    String RN_HAYSTACK =
            "LOWER(CONCAT(COALESCE(r.rn,''),' ',COALESCE(a.name,''),' ',COALESCE(a.street,''),' '," +
            "COALESCE(a.streetNumber,''),' ',COALESCE(a.city,''),' ',COALESCE(l.firstName,''),' '," +
            "COALESCE(l.lastName,''),' ',COALESCE(l.legalEntityName,''),' ',COALESCE(a.county,'')))";

    /** One AND-ed clause per token slot; null slots are no-ops. Tokens are already lowercased by the service. */
    String RN_TOKENS =
            " AND (CAST(:tok0 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok0 AS string), '%'))" +
            " AND (CAST(:tok1 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok1 AS string), '%'))" +
            " AND (CAST(:tok2 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok2 AS string), '%'))" +
            " AND (CAST(:tok3 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok3 AS string), '%'))" +
            " AND (CAST(:tok4 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok4 AS string), '%'))" +
            " AND (CAST(:tok5 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok5 AS string), '%'))" +
            " AND (CAST(:tok6 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok6 AS string), '%'))" +
            " AND (CAST(:tok7 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok7 AS string), '%'))" +
            " AND (CAST(:tok8 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok8 AS string), '%'))" +
            " AND (CAST(:tok9 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok9 AS string), '%'))";

    /** Per-field discrete filters (each optional, case-insensitive substring) + existing county/type filters. */
    String RN_FILTERS =
            " AND (CAST(:rb AS string) IS NULL OR LOWER(r.rn) LIKE CONCAT('%', LOWER(CAST(:rb AS string)), '%'))" +
            " AND (CAST(:city AS string) IS NULL OR LOWER(a.city) LIKE CONCAT('%', LOWER(CAST(:city AS string)), '%'))" +
            " AND (CAST(:street AS string) IS NULL OR LOWER(a.street) LIKE CONCAT('%', LOWER(CAST(:street AS string)), '%'))" +
            " AND (CAST(:name AS string) IS NULL OR LOWER(COALESCE(a.name,'')) LIKE CONCAT('%', LOWER(CAST(:name AS string)), '%'))" +
            " AND (CAST(:lessor AS string) IS NULL OR LOWER(CONCAT(COALESCE(l.firstName,''),' ',COALESCE(l.lastName,''),' ',COALESCE(l.legalEntityName,''))) LIKE CONCAT('%', LOWER(CAST(:lessor AS string)), '%'))" +
            " AND (CAST(:county AS string) IS NULL OR LOWER(a.county) = LOWER(CAST(:county AS string)))" +
            // Opseg službenika (CountyScope): nazivi njegove županije kako su zapisani na
            // smještaju. Lista je uvijek neprazna; kad opseg nije ograničen, ne koristi se.
            " AND (CAST(:scopeMode AS string) IS NULL OR a.county IN :scopeCounties)" +
            " AND (CAST(:municipality AS string) IS NULL OR LOWER(a.city) = LOWER(CAST(:municipality AS string)))" +
            " AND (:typeId IS NULL OR a.accommodationTypeId = :typeId)" +
            " AND (CAST(:foreignOnly AS string) IS NULL OR l.lessorOib IS NULL)" +
            // Radna lista „rok ističe uskoro": namjerno bez donje granice — već istekli, a još
            // neobrađeni rokovi (job se vrti jednom dnevno) moraju ostati vidljivi, inače bi
            // predmet ispao s liste točno onda kad je najhitniji.
            // BEZ CAST-a, za razliku od filtera iznad. CAST(:p AS string) je trik da `IS NULL`
            // prođe na Postgresu kad Hibernate ne može zaključiti tip parametra; za string je
            // bezopasan jer Postgres dopušta cast(bytea as text), pa se netočno vezan null nikad
            // ne primijeti. Za datum NE prolazi: `cast(bytea as date)` je zabranjen i upit puca s
            // `ERROR: cannot cast type bytea to date`. Ovdje cast nije ni potreban — tip se
            // zaključuje iz usporedbe `r.suspensionDeadline <= :deadlineBefore`, pa Hibernate veže
            // null kao DATE. Ne vraćati CAST.
            // Rok trenutnog postupka (T12): svaki rok postoji samo u svom prijedlogu (RnEntity.applyStatus
            // briše ga izvan njega), pa je dovoljno pitati oba — SUSPENSION_PROPOSED ima samo
            // suspensionDeadline, WITHDRAWAL_PROPOSED samo withdrawalDeadline.
            " AND (:deadlineBefore IS NULL" +
            "      OR (r.suspensionDeadline IS NOT NULL AND r.suspensionDeadline <= :deadlineBefore)" +
            "      OR (r.withdrawalDeadline IS NOT NULL AND r.withdrawalDeadline <= :deadlineBefore))" +
            " AND (CAST(:decision AS string) IS NULL OR " + CATEGORIZATION_SHOWN + " = CAST(:decision AS string))";

    /**
     * Filtar „Verificiran". Verifikacija se ne čita iz str_rn nego iz eTurizma, pa skup
     * verificiranih objekata dolazi izvana ({@link RnRegistrySearch}): {@code :verifiedIds} su
     * sirovi {@code accommodation.facility_id} kakvi stoje u bazi. {@code IN} = verificirani;
     * {@code NOT_IN} = svi ostali, uključujući RB novog objekta (bez facilityId). Kad je
     * {@code :verifiedMode} NULL, lista se ne koristi, ali mora biti neprazna (v. {@link RnService}).
     */
    String RN_VERIFIED_FILTER =
            " AND (CAST(:verifiedMode AS string) IS NULL" +
            "      OR (CAST(:verifiedMode AS string) = 'IN' AND a.facilityId IN :verifiedIds)" +
            "      OR (CAST(:verifiedMode AS string) = 'NOT_IN'" +
            "          AND (a.facilityId IS NULL OR a.facilityId NOT IN :verifiedIds)))";

    String RN_FROM =
            " FROM RnEntity r" +
            " JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId" +
            " LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId" +
            " LEFT JOIN LessorEntity l ON l.lessorId =" +
            "     (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)" +
            // Prijedlog povlačenja iz suspenzije javno je suspendiran (T12): pogled valjanih ga
            // isključuje, pogled nevaljanih ga dodaje (RnRegistryView#validity).
            " WHERE (r.status IN :statuses" +
            "        OR (CAST(:validity AS string) = 'INVALID'" +
            "            AND r.status = com.str.backend.domain.RnStatus.WITHDRAWAL_PROPOSED" +
            "            AND r.withdrawalFromSuspended = true))" +
            "   AND (CAST(:validity AS string) IS NULL OR CAST(:validity AS string) <> 'VALID'" +
            "        OR r.status <> com.str.backend.domain.RnStatus.WITHDRAWAL_PROPOSED" +
            "        OR r.withdrawalFromSuspended = false)";

    @Transactional(readOnly = true)
    @Query(value = "SELECT new com.str.backend.rn.dto.RnSummaryDto("
            + " r.rn, r.status, r.issueDate, r.validFrom, r.validTo, r.suspensionDeadline,"
            + " a.accommodationId, a.county, a.city, a.street, a.streetNumber,"
            + " a.name, t.name,"
            + " l.lessorId, l.firstName, l.lastName, l.legalEntityName,"
            + " a.facilityId, " + CATEGORIZATION_REQUIRED + ", " + CATEGORIZATION_STATUS + ","
            + " " + CATEGORIZATION_REGISTERED + ","
            + " r.withdrawalDeadline, r.withdrawalFromSuspended)"
            + RN_FROM + RN_TOKENS + RN_FILTERS + RN_VERIFIED_FILTER,
            countQuery = "SELECT COUNT(r)" + RN_FROM + RN_TOKENS + RN_FILTERS + RN_VERIFIED_FILTER)
    Page<RnSummaryDto> searchRegistry(
            @Param("statuses") List<RnStatus> statuses,
            @Param("validity") String validity,
            @Param("tok0") String tok0, @Param("tok1") String tok1, @Param("tok2") String tok2,
            @Param("tok3") String tok3, @Param("tok4") String tok4, @Param("tok5") String tok5,
            @Param("tok6") String tok6, @Param("tok7") String tok7, @Param("tok8") String tok8,
            @Param("tok9") String tok9,
            @Param("county") String county,
            @Param("scopeMode") String scopeMode,
            @Param("scopeCounties") Collection<String> scopeCounties,
            @Param("municipality") String municipality,
            @Param("typeId") Long typeId,
            @Param("foreignOnly") String foreignOnly,
            @Param("rb") String rb,
            @Param("city") String city,
            @Param("street") String street,
            @Param("name") String name,
            @Param("lessor") String lessor,
            @Param("deadlineBefore") LocalDate deadlineBefore,
            @Param("decision") String decision,
            @Param("verifiedMode") String verifiedMode,
            @Param("verifiedIds") Collection<String> verifiedIds,
            Pageable pageable);

    /**
     * eTurizam objekti RB-ova koje registar s ovim filtrima prikazuje (bez filtra „Verificiran") —
     * prvi korak tog filtra: njihova se verifikacija pita eTurizam ({@link RnRegistrySearch}).
     */
    @Transactional(readOnly = true)
    @Query("SELECT DISTINCT a.facilityId" + RN_FROM + RN_TOKENS + RN_FILTERS
            + " AND a.facilityId IS NOT NULL")
    List<String> findRegistryFacilityIds(
            @Param("statuses") List<RnStatus> statuses,
            @Param("validity") String validity,
            @Param("tok0") String tok0, @Param("tok1") String tok1, @Param("tok2") String tok2,
            @Param("tok3") String tok3, @Param("tok4") String tok4, @Param("tok5") String tok5,
            @Param("tok6") String tok6, @Param("tok7") String tok7, @Param("tok8") String tok8,
            @Param("tok9") String tok9,
            @Param("county") String county,
            @Param("scopeMode") String scopeMode,
            @Param("scopeCounties") Collection<String> scopeCounties,
            @Param("municipality") String municipality,
            @Param("typeId") Long typeId,
            @Param("foreignOnly") String foreignOnly,
            @Param("rb") String rb,
            @Param("city") String city,
            @Param("street") String street,
            @Param("name") String name,
            @Param("lessor") String lessor,
            @Param("deadlineBefore") LocalDate deadlineBefore,
            @Param("decision") String decision);

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.str.backend.rn.dto.RnDetailDto(
                r.rn, r.status, r.issueDate, r.validFrom, r.validTo, r.suspensionDeadline,
                r.createdAt, r.updatedAt, r.submissionId,
                a.accommodationId, a.county, a.city, a.settlement, a.street, a.streetNumber,
                a.name, t.name, a.maxBeds, a.category,
                l.lessorId, l.firstName, l.lastName, l.legalEntityName, l.email, l.lessorOib,
                l.legalEntityOwner, c.name, l.legalEntityCity, l.legalEntityRegistrationNumber,
                l.representativeOib, l.legalRepresentativeName,
                l.representativeEmail, l.representativePhone, l.representativeAddress,
                r.withdrawalDeadline, r.withdrawalFromSuspended,
                g.name
            )
            FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId
            LEFT JOIN ObjectGroupEntity g ON g.code = t.group
            LEFT JOIN LessorEntity l ON l.lessorId =
                (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)
            LEFT JOIN CountryEntity c ON c.id = l.legalEntityCountryId
            WHERE r.rn = :rn
            """)
    Optional<RnDetailDto> findDetail(@Param("rn") String rn);

    /** Dopuna {@link #findDetail} za prikaz detalja RB-a; isti lessor (snapshot iz submissiona). */
    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.str.backend.rn.dto.RnDetailExtras(
                a.offering, a.offerType,
                l.contactName, l.phoneNumber, l.mobileNumber,
                l.street, l.streetNumber, l.place, l.county, c.name
            )
            FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN LessorEntity l ON l.lessorId =
                (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)
            LEFT JOIN CountryEntity c ON c.id = l.countryOfResidenceId
            WHERE r.rn = :rn
            """)
    Optional<RnDetailExtras> findDetailExtras(@Param("rn") String rn);

    /** STR-1.4-001: advertise-safe public projection for a single RN (no lessor identity). */
    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.str.backend.rn.dto.RnPublicView(
                r.rn, a.name, a.category, a.street, a.streetNumber, a.city, coalesce(g.name, t.group), t.name)
            FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId
            LEFT JOIN ObjectGroupEntity g ON g.code = t.group
            WHERE r.rn = :rn
            """)
    Optional<RnPublicView> findPublicView(@Param("rn") String rn);

    /**
     * STR-3.3 (T4): JLS (općina/grad) i najveći broj kreveta po RB-u, za mjesečnu dostavu DZS-u preko
     * SDEP-a (čl. 9. st. 2. STR Uredbe). Bez podataka o iznajmljivaču. RB koji ne postoji jednostavno
     * nije u rezultatu.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.str.backend.rn.dto.RnAccommodationStatsDto(r.rn, a.city, a.maxBeds)
            FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE r.rn IN :rns
            """)
    List<RnAccommodationStatsDto> findAccommodationStats(@Param("rns") Collection<String> rns);

    /** Non-EU popis. Stanje rješenja o kategorizaciji kao na NIAS popisu ({@link #findByLessorOib}). */
    @Transactional(readOnly = true)
    @Query("SELECT new com.str.backend.lessor.LessorRnSummaryDto("
            + " r.rn, r.status, r.issueDate,"
            + " a.name, a.street, a.streetNumber, a.city,"
            + " t.name,"
            + " " + CATEGORIZATION_REQUIRED + ","
            + " " + CATEGORIZATION_STATUS + ","
            + " " + OBJECTION_STATUS + ","
            + " a.facilityId, r.withdrawalFromSuspended)"
            + " FROM RnEntity r"
            + " JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId"
            + " LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId"
            + " WHERE r.submissionId IN ("
            + " SELECT s.submissionId FROM SubmissionEntity s WHERE s.lessorId = :lessorId)"
            + " ORDER BY r.issueDate DESC")
    List<LessorRnSummaryDto> findByLessorId(@Param("lessorId") UUID lessorId);

    /**
     * Pripada li RB ovom iznajmljivaču. Koristi se za pristup aktima: stranka smije preuzeti
     * akt koji se na nju odnosi (ona mu je i adresat), ali ne i tuđi.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT COUNT(r) > 0 FROM RnEntity r
            WHERE r.rn = :rn AND r.submissionId IN (
                SELECT s.submissionId FROM SubmissionEntity s WHERE s.lessorId = :lessorId
            )
            """)
    boolean isOwnedByLessor(@Param("rn") String rn, @Param("lessorId") UUID lessorId);

    /** Kao {@link #isOwnedByLessor}, ali po OIB-u — NIAS flow (lessorId nije stabilan
     *  između prijava iste osobe, pa se vlasništvo nad RB-om provjerava po OIB-u iz SAML-a). */
    @Transactional(readOnly = true)
    @Query("""
            SELECT COUNT(r) > 0 FROM RnEntity r
            WHERE r.rn = :rn AND r.submissionId IN (
                SELECT s.submissionId FROM SubmissionEntity s
                JOIN LessorEntity l ON l.lessorId = s.lessorId
                WHERE l.lessorOib = :oib
            )
            """)
    boolean isOwnedByOib(@Param("rn") String rn, @Param("oib") String oib);

    /** NIAS flow: lessorId is not stable across submissions by the same person
     *  (each NIAS registration creates a new LessorEntity snapshot), so the
     *  "Moji registracijski brojevi" view for NIAS users matches by OIB.
     *
     *  <p>Stanje rješenja o kategorizaciji: {@link #CATEGORIZATION_STATUS}, isto kao na registru. */
    @Transactional(readOnly = true)
    @Query("SELECT new com.str.backend.lessor.LessorRnSummaryDto("
            + " r.rn, r.status, r.issueDate,"
            + " a.name, a.street, a.streetNumber, a.city,"
            + " t.name,"
            + " " + CATEGORIZATION_REQUIRED + ","
            + " " + CATEGORIZATION_STATUS + ","
            + " " + OBJECTION_STATUS + ","
            + " a.facilityId, r.withdrawalFromSuspended)"
            + " FROM RnEntity r"
            + " JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId"
            + " LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId"
            + " JOIN SubmissionEntity s ON s.submissionId = r.submissionId"
            + " JOIN LessorEntity l ON l.lessorId = s.lessorId"
            + " WHERE l.lessorOib = :oib"
            + " ORDER BY r.issueDate DESC")
    List<LessorRnSummaryDto> findByLessorOib(@Param("oib") String oib);

    /** Duplicate-location check: is there a still-standing (ACTIVE, SUSPENSION_PROPOSED,
     *  SUSPENDED or WITHDRAWAL_PROPOSED) RN for an accommodation
     *  on this exact address (county + city + street + streetNumber, case- and whitespace-
     *  insensitive), optionally filtered to a specific lessor by OIB? When {@code oib} is
     *  null the address match alone is enough to surface a conflict; when set, the lessor
     *  must also match. OIB-based match (rather than lessorId) — the NIAS flow creates a
     *  new LessorEntity per registration, so lessorId is not stable for the same person. */
    @Transactional(readOnly = true)
    @Query("""
            SELECT r.rn FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN SubmissionEntity s ON s.submissionId = r.submissionId
            LEFT JOIN LessorEntity l ON l.lessorId = s.lessorId
            WHERE r.status IN (com.str.backend.domain.RnStatus.ACTIVE,
                               com.str.backend.domain.RnStatus.SUSPENSION_PROPOSED,
                               com.str.backend.domain.RnStatus.SUSPENDED,
                               com.str.backend.domain.RnStatus.WITHDRAWAL_PROPOSED)
              AND LOWER(TRIM(a.county)) = LOWER(TRIM(:county))
              AND LOWER(TRIM(a.city)) = LOWER(TRIM(:city))
              AND LOWER(TRIM(a.street)) = LOWER(TRIM(:street))
              AND LOWER(TRIM(a.streetNumber)) = LOWER(TRIM(:streetNumber))
              AND (CAST(:oib AS string) IS NULL OR l.lessorOib = CAST(:oib AS string))
            ORDER BY r.issueDate DESC
            """)
    List<String> findActiveOrSuspendedRnByAddressAndOib(
            @Param("county") String county,
            @Param("city") String city,
            @Param("street") String street,
            @Param("streetNumber") String streetNumber,
            @Param("oib") String oib);

    /**
     * T12: drugi još važeći broj istog iznajmljivača na istoj adresi kao broj {@code rn}. Adresa je ista
     * kao kod {@link #findActiveOrSuspendedRnByAddressAndOib} i ne razlikuje stanove u zgradi, pa se
     * uspoređuje samo s brojevima istog iznajmljivača: po OIB-u, a non-EU iznajmljivač (bez OIB-a) po
     * svom zapisu. Broj susjednog apartmana drugog vlasnika tako ne blokira prigovor na povlačenje.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT o.rn FROM RnEntity me
            JOIN AccommodationEntity ma ON ma.accommodationId = me.accommodationId
            JOIN SubmissionEntity ms ON ms.submissionId = me.submissionId
            JOIN LessorEntity ml ON ml.lessorId = ms.lessorId,
            RnEntity o
            JOIN AccommodationEntity oa ON oa.accommodationId = o.accommodationId
            JOIN SubmissionEntity os ON os.submissionId = o.submissionId
            JOIN LessorEntity ol ON ol.lessorId = os.lessorId
            WHERE me.rn = :rn
              AND o.rn <> me.rn
              AND o.status IN (com.str.backend.domain.RnStatus.ACTIVE,
                               com.str.backend.domain.RnStatus.SUSPENSION_PROPOSED,
                               com.str.backend.domain.RnStatus.SUSPENDED,
                               com.str.backend.domain.RnStatus.WITHDRAWAL_PROPOSED)
              AND LOWER(TRIM(oa.county)) = LOWER(TRIM(ma.county))
              AND LOWER(TRIM(oa.city)) = LOWER(TRIM(ma.city))
              AND LOWER(TRIM(oa.street)) = LOWER(TRIM(ma.street))
              AND LOWER(TRIM(oa.streetNumber)) = LOWER(TRIM(ma.streetNumber))
              AND ((COALESCE(TRIM(ml.lessorOib), '') <> '' AND ol.lessorOib = ml.lessorOib)
                   OR (COALESCE(TRIM(ml.lessorOib), '') = '' AND ol.lessorId = ml.lessorId))
            """)
    List<String> findStandingRnsOfSameLessorAtSameAddress(@Param("rn") String rn);
}
