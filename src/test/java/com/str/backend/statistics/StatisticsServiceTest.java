package com.str.backend.statistics;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.rn.RnRepository;
import com.str.backend.statistics.dto.StrResponse;
import com.str.backend.statistics.dto.CountyStrDto;
import com.str.backend.str.StrFacilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatisticsServiceTest {

    private AccommodationRepository accommodationRepository;
    private RnRepository rnRepository;
    private CountyRepository countyRepository;
    private StrFacilityRepository facilityRepository;
    private StatisticsService service;

    @BeforeEach
    void setUp() {
        accommodationRepository = mock(AccommodationRepository.class);
        rnRepository = mock(RnRepository.class);
        countyRepository = mock(CountyRepository.class);
        facilityRepository = mock(StrFacilityRepository.class);
        service = new StatisticsService(accommodationRepository, rnRepository, countyRepository, facilityRepository);
    }

    @Test
    void str_emitsRowForEveryActiveCounty_evenWithZeroData() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Splitsko-dalmatinska županija"),
                county(2L, "Grad Zagreb")
        ));
        when(accommodationRepository.countByCounty()).thenReturn(List.of());
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of());
        when(facilityRepository.countByActiveTrue()).thenReturn(0L);

        StrResponse res = service.str(null, null);

        assertThat(res.counties()).hasSize(2);
        assertThat(res.counties()).allSatisfy(c -> {
            assertThat(c.accommodations()).isZero();
            assertThat(c.activeRn()).isZero();
            assertThat(c.registrationRate()).isZero();
        });
        assertThat(res.totals().totalObjects()).isZero();
        assertThat(res.totals().totalRn()).isZero();
    }

    @Test
    void str_aggregatesRnsByStatusPerCounty_andComputesRate() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Splitsko-dalmatinska županija")
        ));
        when(accommodationRepository.countByCounty()).thenReturn(List.of(
                accCount("Splitsko-dalmatinska županija", 10L)
        ));
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of(
                rnCount("Splitsko-dalmatinska županija", RnStatus.ACTIVE, 4L),
                rnCount("Splitsko-dalmatinska županija", RnStatus.SUSPENDED, 1L),
                rnCount("Splitsko-dalmatinska županija", RnStatus.WITHDRAWN, 1L),
                rnCount("Splitsko-dalmatinska županija", RnStatus.IN_PROCESSING, 99L)
        ));
        when(facilityRepository.countByActiveTrue()).thenReturn(10L);

        StrResponse res = service.str(null, null);

        CountyStrDto row = res.counties().get(0);
        assertThat(row.accommodations()).isEqualTo(10L);
        assertThat(row.activeRn()).isEqualTo(4L);
        assertThat(row.suspendedRn()).isEqualTo(1L);
        assertThat(row.withdrawnRn()).isEqualTo(1L);
        assertThat(row.registrationRate()).isEqualTo(40.0);
        // IN_PROCESSING RNs are ignored by design — they aren't yet issued.
    }

    @Test
    void str_surfacesOrphanCountiesUnderOtherBucket_andTotalsRemainEqualToSumOfRows() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Zagrebačka županija")
        ));
        // 5 accommodations in a county that isn't active (e.g. renamed/typo)
        when(accommodationRepository.countByCounty()).thenReturn(List.of(
                accCount("Zagrebačka županija", 3L),
                accCount("Nepoznata županija", 5L)
        ));
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of(
                rnCount("Nepoznata županija", RnStatus.ACTIVE, 2L)
        ));
        when(facilityRepository.countByActiveTrue()).thenReturn(20L);

        StrResponse res = service.str(null, null);

        assertThat(res.counties()).hasSize(2);
        assertThat(res.counties())
                .extracting(CountyStrDto::countyId)
                .containsExactlyInAnyOrder("1", StatisticsService.OTHER_COUNTY_ID);

        long sumActive = res.counties().stream().mapToLong(CountyStrDto::activeRn).sum();
        assertThat(res.totals().totalObjects()).isEqualTo(20L);
        assertThat(res.totals().totalRn()).isEqualTo(sumActive).isEqualTo(2L);
    }

    @Test
    void str_sortsByAccommodationsDescThenByName() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "A-zupanija"),
                county(2L, "B-zupanija"),
                county(3L, "C-zupanija")
        ));
        when(accommodationRepository.countByCounty()).thenReturn(List.of(
                accCount("A-zupanija", 1L),
                accCount("B-zupanija", 5L),
                accCount("C-zupanija", 5L)
        ));
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of());
        when(facilityRepository.countByActiveTrue()).thenReturn(0L);

        StrResponse res = service.str(null, null);

        assertThat(res.counties())
                .extracting(CountyStrDto::countyName)
                .containsExactly("B-zupanija", "C-zupanija", "A-zupanija");
    }

    private static CountyEntity county(Long id, String name) {
        CountyEntity c = new CountyEntity() {};
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "name", name);
        ReflectionTestUtils.setField(c, "zuRb", id.intValue());
        return c;
    }

    private static AccommodationRepository.CountyCount accCount(String county, long count) {
        return new AccommodationRepository.CountyCount() {
            @Override public String getCounty() { return county; }
            @Override public long getCount() { return count; }
        };
    }

    private static RnRepository.CountyStatusCount rnCount(String county, RnStatus status, long count) {
        return new RnRepository.CountyStatusCount() {
            @Override public String getCounty() { return county; }
            @Override public RnStatus getStatus() { return status; }
            @Override public long getCount() { return count; }
        };
    }

    private static RnRepository.CountyCount rnAccCount(String county, long count) {
        return new RnRepository.CountyCount() {
            @Override public String getCounty() { return county; }
            @Override public long getCount() { return count; }
        };
    }

    @Test
    void str_withDateFilter_usesRnScopedAccommodationsAndTotalObjects() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Splitsko-dalmatinska županija")
        ));
        when(rnRepository.countDistinctAccommodationsByCountyBetween(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of(rnAccCount("Splitsko-dalmatinska županija", 3L)));
        when(rnRepository.countByCountyAndStatusBetween(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of(rnCount("Splitsko-dalmatinska županija", RnStatus.ACTIVE, 3L)));

        StrResponse res = service.str(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(res.counties()).hasSize(1);
        assertThat(res.counties().get(0).accommodations()).isEqualTo(3L);
        assertThat(res.totals().totalObjects()).isEqualTo(3L);
        assertThat(res.totals().totalRn()).isEqualTo(3L);
        // Unfiltered queries must not be hit when the filter is active.
        verify(accommodationRepository, never()).countByCounty();
        verify(facilityRepository, never()).countByActiveTrue();
        verify(rnRepository, never()).countByCountyAndStatus();
    }

    @Test
    void str_withDateFilter_emptyPeriodReportsZeros() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Splitsko-dalmatinska županija")
        ));
        when(rnRepository.countDistinctAccommodationsByCountyBetween(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of());
        when(rnRepository.countByCountyAndStatusBetween(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of());

        StrResponse res = service.str(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(res.counties()).hasSize(1);
        assertThat(res.counties().get(0).accommodations()).isZero();
        assertThat(res.totals().totalObjects()).isZero();
        assertThat(res.totals().totalRn()).isZero();
    }

}
