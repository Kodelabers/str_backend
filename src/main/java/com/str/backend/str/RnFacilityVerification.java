package com.str.backend.str;

import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.str.StrFacilityRepository.FacilityVerificationRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Stupac „Verificiran" na „Mojim registracijskim brojevima": je li objekt uz RB danas verificiran
 * u eTurizmu, po istom pravilu kao popis objekata
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

    private final StrFacilityRepository facilityRepository;

    public RnFacilityVerification(StrFacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    public List<LessorRnSummaryDto> withFacilityVerified(List<LessorRnSummaryDto> rows) {
        List<Long> ids = rows.stream()
                .map(row -> parseId(row.facilityId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Boolean> verified;
        try {
            verified = ids.isEmpty()
                    ? Map.of()
                    : facilityRepository.findObjectVerification(ids).stream()
                            .collect(Collectors.toMap(FacilityVerificationRow::getFacilityId,
                                    row -> Boolean.TRUE.equals(row.getVerified()),
                                    (a, b) -> a && b));
        } catch (DataAccessException e) {
            log.warn("rn_facility_verification failed facilities={} error={}",
                    ids.size(), e.getClass().getSimpleName());
            return rows;
        }
        return rows.stream()
                .map(row -> {
                    Long id = parseId(row.facilityId());
                    return row.withFacilityVerified(id != null && verified.getOrDefault(id, false));
                })
                .toList();
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
