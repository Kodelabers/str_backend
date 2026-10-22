package com.str.backend.str;

import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.str.StrFacilityRepository.FacilityVerificationRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stupac „Verificiran" na „Mojim registracijskim brojevima" i na registru RB-ova: je li objekt uz
 * RB danas verificiran u eTurizmu, po istom pravilu kao popis objekata
 * ({@link StrFacilityRepository#findObjectVerification}).
 *
 * <p>RB novog objekta (bez eTurizam {@code facilityId}) nije verificiran — takvog objekta u
 * eTurizmu nema (odluka 7. 10. 2026.). Isto vrijedi za {@code facilityId} kojeg u eTurizmu više
 * nema. Svi brojevi idu jednim upitom.
 *
 * <p>Kad upit nad eTurizmom padne, popis se vraća bez stupca ({@code facilityVerified = null},
 * frontend prikazuje „-"): popis vlastitih brojeva ne smije ovisiti o dostupnosti eTurizma.
 * Namjerno bez {@code @Transactional} — iznimka iz repozitorija označila bi vanjsku transakciju
 * kao rollback-only, pa bi i uhvaćena greška završila kao {@code UnexpectedRollbackException}.
 */
@Service
public class RnFacilityVerification {

    private static final Logger log = LoggerFactory.getLogger(RnFacilityVerification.class);

    static final int CHUNK_SIZE = 5_000;

    private final StrFacilityRepository facilityRepository;

    public RnFacilityVerification(StrFacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    public List<LessorRnSummaryDto> withFacilityVerified(List<LessorRnSummaryDto> rows) {
        Set<String> verified;
        try {
            verified = verifiedFacilityIds(rows.stream().map(LessorRnSummaryDto::facilityId).toList());
        } catch (DataAccessException e) {
            return rows;
        }
        return rows.stream()
                .map(row -> row.withFacilityVerified(
                        row.facilityId() != null && verified.contains(row.facilityId())))
                .toList();
    }

    /**
     * Koji su od zadanih {@code accommodation.facility_id} (sirovih, kakvi stoje u bazi) danas
     * verificirani; svi ostali — prazni, nebrojčani, nepoznati eTurizmu — nisu. Vraća sirove
     * vrijednosti, da ih pozivatelj može usporediti s bazom bez ponovnog parsiranja (registar ih
     * šalje natrag u upit kao filtar „Verificiran").
     *
     * <p>eTurizam se pita u komadima od {@value #CHUNK_SIZE}: upit koristi listu dvaput, a
     * PostgreSQL JDBC prima najviše 32.767 parametara po upitu.
     *
     * @throws DataAccessException kad eTurizam nije dostupan — pozivatelj odlučuje vraća li
     * redove bez stupca ili odbija filtar
     */
    public Set<String> verifiedFacilityIds(Collection<String> facilityIds) {
        Map<Long, List<String>> rawById = new LinkedHashMap<>();
        for (String raw : facilityIds) {
            Long id = parseId(raw);
            if (id != null) {
                rawById.computeIfAbsent(id, k -> new ArrayList<>()).add(raw);
            }
        }
        if (rawById.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = List.copyOf(rawById.keySet());
        Map<Long, Boolean> verified = new HashMap<>();
        try {
            for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
                List<Long> chunk = ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()));
                for (FacilityVerificationRow row : facilityRepository.findObjectVerification(chunk)) {
                    verified.merge(row.getFacilityId(), Boolean.TRUE.equals(row.getVerified()),
                            Boolean::logicalAnd);
                }
            }
        } catch (DataAccessException e) {
            log.warn("rn_facility_verification failed facilities={} error={}",
                    ids.size(), e.getClass().getSimpleName());
            throw e;
        }
        Set<String> result = new HashSet<>();
        rawById.forEach((id, raws) -> {
            if (verified.getOrDefault(id, false)) {
                result.addAll(raws);
            }
        });
        return result;
    }

    /** {@code accommodation.facility_id} je tekst; nebrojčan ili prazan znači „nema objekta". */
    private static Long parseId(String facilityId) {
        if (facilityId == null || facilityId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(facilityId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
