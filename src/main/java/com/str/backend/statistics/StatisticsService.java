package com.str.backend.statistics;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyNames;
import com.str.backend.address.CountyRepository;
import com.str.backend.auth.internal.CountyScope;
import com.str.backend.domain.RnStatus;
import com.str.backend.rn.RnRepository;
import com.str.backend.statistics.dto.StrResponse;
import com.str.backend.statistics.dto.StrTotalsDto;
import com.str.backend.statistics.dto.CountyStrDto;
import com.str.backend.str.StrFacilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatisticsService {

    /** Synthetic county id for accommodations whose county name doesn't match any known CountyEntity. */
    static final String OTHER_COUNTY_ID = "OTHER";

    private final AccommodationRepository accommodationRepository;
    private final RnRepository rnRepository;
    private final CountyRepository countyRepository;
    private final StrFacilityRepository facilityRepository;

    public StatisticsService(AccommodationRepository accommodationRepository,
                             RnRepository rnRepository,
                             CountyRepository countyRepository,
                             StrFacilityRepository facilityRepository) {
        this.accommodationRepository = accommodationRepository;
        this.rnRepository = rnRepository;
        this.countyRepository = countyRepository;
        this.facilityRepository = facilityRepository;
    }

    /**
     * Naziv županije na smještaju uspoređuje se s registrom po {@link CountyNames#key}: isti podatak
     * stoji u više oblika („…županija", bez nastavka, velikim slovima), pa svaki oblik ide u svoju
     * županiju, a u „Ostalo" samo naziv koji ne odgovara nijednoj. Isto pravilo vrijedi za prikaz
     * službenika, pa ministarstvo i županija za istu županiju vide isti broj.
     *
     * <p>Uz ograničen opseg ({@link CountyScope#restricted()}) samo red županije službenika, bez
     * retka „Ostalo", a {@code totalObjects} su aktivni eTurizam objekti te županije.
     */
    @Transactional(readOnly = true)
    public StrResponse str(LocalDate from, LocalDate to, CountyScope scope) {
        List<CountyEntity> activeCounties = countyRepository.findAllByOrderByZuRb();
        Map<String, CountyEntity> countyByKey = new HashMap<>();
        for (CountyEntity c : activeCounties) countyByKey.putIfAbsent(CountyNames.key(c.getName()), c);

        // When a date range is set, "accommodations" + totalObjects must be scoped to the
        // same period as the RN counts — otherwise empty periods report inflated denominators
        // and the coverage rate is meaningless. With no filter we fall back to the full
        // accommodation population and the eTurizam facility headcount (real coverage view).
        boolean filtered = from != null && to != null;

        Map<String, Long> accByCounty = new HashMap<>();
        if (filtered) {
            for (var row : rnRepository.countDistinctAccommodationsByCountyBetween(from, to)) {
                accByCounty.merge(row.getCounty(), row.getCount(), Long::sum);
            }
        } else {
            for (var row : accommodationRepository.countByCounty()) {
                accByCounty.merge(row.getCounty(), row.getCount(), Long::sum);
            }
        }

        List<RnRepository.CountyStatusCount> rnCounts;
        if (filtered) {
            rnCounts = rnRepository.countByCountyAndStatusBetween(from, to);
        } else {
            rnCounts = rnRepository.countByCountyAndStatus();
        }

        Map<String, Map<RnStatus, Long>> rnByCounty = new HashMap<>();
        for (var row : rnCounts) {
            rnByCounty
                    .computeIfAbsent(row.getCounty(), k -> new EnumMap<>(RnStatus.class))
                    .merge(row.getStatus(), row.getCount(), Long::sum);
        }

        if (scope.restricted()) {
            return countyOnly(scope, filtered, accByCounty, rnByCounty);
        }

        // Zapisani nazivi grupiraju se po županiji iz registra; što ne odgovara nijednoj ide u
        // „Ostalo", da zbroj redaka ostane jednak ukupnom broju.
        Map<Long, Long> accByCountyId = new HashMap<>();
        long otherAcc = 0L;
        for (var entry : accByCounty.entrySet()) {
            CountyEntity c = countyByKey.get(CountyNames.key(entry.getKey()));
            if (c != null) accByCountyId.merge(c.getId(), entry.getValue(), Long::sum);
            else otherAcc += entry.getValue();
        }
        Map<Long, Map<RnStatus, Long>> rnByCountyId = new HashMap<>();
        Map<RnStatus, Long> otherRn = new EnumMap<>(RnStatus.class);
        for (var entry : rnByCounty.entrySet()) {
            CountyEntity c = countyByKey.get(CountyNames.key(entry.getKey()));
            Map<RnStatus, Long> target = c != null
                    ? rnByCountyId.computeIfAbsent(c.getId(), k -> new EnumMap<>(RnStatus.class))
                    : otherRn;
            entry.getValue().forEach((status, count) -> target.merge(status, count, Long::sum));
        }

        List<CountyStrDto> rows = new ArrayList<>(activeCounties.size() + 1);
        for (CountyEntity c : activeCounties) {
            rows.add(buildRow(String.valueOf(c.getId()), c.getName(),
                    accByCountyId.getOrDefault(c.getId(), 0L),
                    rnByCountyId.getOrDefault(c.getId(), Map.of())));
        }
        if (otherAcc > 0 || !otherRn.isEmpty()) {
            rows.add(buildRow(OTHER_COUNTY_ID, "Ostalo", otherAcc, otherRn));
        }

        rows.sort(Comparator
                .comparingLong(CountyStrDto::accommodations).reversed()
                .thenComparing(CountyStrDto::countyName));

        long totalActive = 0, totalSuspended = 0, totalRevoked = 0, totalWithdrawn = 0;
        for (CountyStrDto r : rows) {
            totalActive += r.activeRn();
            totalSuspended += r.suspendedRn();
            totalRevoked += r.revokedRn();
            totalWithdrawn += r.withdrawnRn();
        }

        long totalObjects = filtered
                ? rows.stream().mapToLong(CountyStrDto::accommodations).sum()
                : facilityRepository.countByActiveTrue();
        long totalRn = totalActive + totalSuspended + totalRevoked + totalWithdrawn;
        StrTotalsDto totals = new StrTotalsDto(totalObjects, totalRn, rate(totalRn, totalObjects));
        return new StrResponse(totals, rows);
    }

    private StrResponse countyOnly(CountyScope scope, boolean filtered, Map<String, Long> accByCounty,
                                   Map<String, Map<RnStatus, Long>> rnByCounty) {
        long accommodations = 0L;
        for (var entry : accByCounty.entrySet()) {
            if (scope.covers(entry.getKey())) accommodations += entry.getValue();
        }
        Map<RnStatus, Long> byStatus = new EnumMap<>(RnStatus.class);
        for (var entry : rnByCounty.entrySet()) {
            if (!scope.covers(entry.getKey())) continue;
            entry.getValue().forEach((status, count) -> byStatus.merge(status, count, Long::sum));
        }
        CountyStrDto row = buildRow(String.valueOf(scope.countyId()), scope.countyName(), accommodations, byStatus);

        long totalObjects = filtered
                ? accommodations
                : facilityRepository.countActiveByCounty().stream()
                        .filter(c -> scope.covers(c.getCounty()))
                        .mapToLong(StrFacilityRepository.CountyCount::getCount)
                        .sum();
        long totalRn = row.activeRn() + row.suspendedRn() + row.revokedRn() + row.withdrawnRn();
        return new StrResponse(new StrTotalsDto(totalObjects, totalRn, rate(totalRn, totalObjects)), List.of(row));
    }

    private static CountyStrDto buildRow(String id, String name, long accommodations,
                                          Map<RnStatus, Long> byStatus) {
        // Prijedlog suspenzije je međukorak s otvorenim rokom, ne suspenzija — RB u tom statusu
        // vrijedi, pa se broji kao aktivan. Bez toga bi ispao iz svih stupaca, a i iz ukupnog
        // broja RB-ova, koji je zbroj ova četiri.
        long active = byStatus.getOrDefault(RnStatus.ACTIVE, 0L)
                + byStatus.getOrDefault(RnStatus.SUSPENSION_PROPOSED, 0L);
        long suspended = byStatus.getOrDefault(RnStatus.SUSPENDED, 0L);
        long revoked = byStatus.getOrDefault(RnStatus.REVOKED, 0L);
        long withdrawn = byStatus.getOrDefault(RnStatus.WITHDRAWN, 0L);
        return new CountyStrDto(id, name, accommodations, active, suspended, revoked, withdrawn,
                rate(active, accommodations));
    }

    private static double rate(long numerator, long denominator) {
        if (denominator == 0) return 0d;
        return Math.round((double) numerator / denominator * 1000d) / 10d;
    }
}
