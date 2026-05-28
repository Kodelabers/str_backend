package com.str.backend.statistics;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.rn.RnRepository;
import com.str.backend.statistics.dto.BpsoResponse;
import com.str.backend.statistics.dto.BpsoTotalsDto;
import com.str.backend.statistics.dto.CountyBpsoDto;
import com.str.backend.str.StrSubjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StatisticsService {

    /** Synthetic county id for accommodations whose county name doesn't match any known CountyEntity. */
    static final String OTHER_COUNTY_ID = "OTHER";

    private final AccommodationRepository accommodationRepository;
    private final RnRepository rnRepository;
    private final CountyRepository countyRepository;
    private final StrSubjectRepository subjectRepository;

    public StatisticsService(AccommodationRepository accommodationRepository,
                             RnRepository rnRepository,
                             CountyRepository countyRepository,
                             StrSubjectRepository subjectRepository) {
        this.accommodationRepository = accommodationRepository;
        this.rnRepository = rnRepository;
        this.countyRepository = countyRepository;
        this.subjectRepository = subjectRepository;
    }

    @Transactional(readOnly = true)
    public BpsoResponse bpso() {
        List<CountyEntity> activeCounties = countyRepository.findAllByOrderByZuRb();
        Set<String> activeNames = new HashSet<>(activeCounties.size());
        for (CountyEntity c : activeCounties) activeNames.add(c.getName());

        Map<String, Long> accByCounty = new HashMap<>();
        for (var row : accommodationRepository.countByCounty()) {
            accByCounty.merge(row.getCounty(), row.getCount(), Long::sum);
        }

        Map<String, Map<RnStatus, Long>> rnByCounty = new HashMap<>();
        for (var row : rnRepository.countByCountyAndStatus()) {
            rnByCounty
                    .computeIfAbsent(row.getCounty(), k -> new EnumMap<>(RnStatus.class))
                    .merge(row.getStatus(), row.getCount(), Long::sum);
        }

        List<CountyBpsoDto> rows = new ArrayList<>(activeCounties.size() + 1);

        for (CountyEntity c : activeCounties) {
            rows.add(buildRow(String.valueOf(c.getId()), c.getName(),
                    accByCounty.getOrDefault(c.getName(), 0L),
                    rnByCounty.getOrDefault(c.getName(), Map.of())));
        }

        // Surface accommodations whose county name doesn't match any active county
        // so totals stay consistent with the sum of rows.
        long otherAcc = 0L;
        Map<RnStatus, Long> otherRn = new EnumMap<>(RnStatus.class);
        for (var entry : accByCounty.entrySet()) {
            if (!activeNames.contains(entry.getKey())) otherAcc += entry.getValue();
        }
        for (var entry : rnByCounty.entrySet()) {
            if (activeNames.contains(entry.getKey())) continue;
            entry.getValue().forEach((status, count) -> otherRn.merge(status, count, Long::sum));
        }
        if (otherAcc > 0 || !otherRn.isEmpty()) {
            rows.add(buildRow(OTHER_COUNTY_ID, "Ostalo", otherAcc, otherRn));
        }

        rows.sort(Comparator
                .comparingLong(CountyBpsoDto::accommodations).reversed()
                .thenComparing(CountyBpsoDto::countyName));

        long totalActive = 0, totalSuspended = 0, totalWithdrawn = 0;
        for (CountyBpsoDto r : rows) {
            totalActive += r.activeRn();
            totalSuspended += r.suspendedRn();
            totalWithdrawn += r.withdrawnRn();
        }

        long totalObjects = subjectRepository.countByActiveTrue();
        long totalRn = totalActive + totalSuspended + totalWithdrawn;
        BpsoTotalsDto totals = new BpsoTotalsDto(totalObjects, totalRn, rate(totalRn, totalObjects));
        return new BpsoResponse(totals, rows);
    }

    private static CountyBpsoDto buildRow(String id, String name, long accommodations,
                                          Map<RnStatus, Long> byStatus) {
        long active = byStatus.getOrDefault(RnStatus.ACTIVE, 0L);
        long suspended = byStatus.getOrDefault(RnStatus.SUSPENDED, 0L);
        long withdrawn = byStatus.getOrDefault(RnStatus.WITHDRAWN, 0L);
        return new CountyBpsoDto(id, name, accommodations, active, suspended, withdrawn,
                rate(active, accommodations));
    }

    private static double rate(long numerator, long denominator) {
        if (denominator == 0) return 0d;
        return Math.round((double) numerator / denominator * 1000d) / 10d;
    }
}
