package com.str.backend.statistics;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.rn.RnRepository;
import com.str.backend.statistics.dto.BpsoResponse;
import com.str.backend.statistics.dto.CountyBpsoDto;
import com.str.backend.str.StrSubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatisticsServiceTest {

    private AccommodationRepository accommodationRepository;
    private RnRepository rnRepository;
    private CountyRepository countyRepository;
    private StrSubjectRepository subjectRepository;
    private StatisticsService service;

    @BeforeEach
    void setUp() {
        accommodationRepository = mock(AccommodationRepository.class);
        rnRepository = mock(RnRepository.class);
        countyRepository = mock(CountyRepository.class);
        subjectRepository = mock(StrSubjectRepository.class);
        service = new StatisticsService(accommodationRepository, rnRepository, countyRepository, subjectRepository);
    }

    @Test
    void bpso_emitsRowForEveryActiveCounty_evenWithZeroData() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Splitsko-dalmatinska županija"),
                county(2L, "Grad Zagreb")
        ));
        when(accommodationRepository.countByCounty()).thenReturn(List.of());
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of());
        when(subjectRepository.countByActiveTrue()).thenReturn(0L);

        BpsoResponse res = service.bpso();

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
    void bpso_aggregatesRnsByStatusPerCounty_andComputesRate() {
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
        when(subjectRepository.countByActiveTrue()).thenReturn(10L);

        BpsoResponse res = service.bpso();

        CountyBpsoDto row = res.counties().get(0);
        assertThat(row.accommodations()).isEqualTo(10L);
        assertThat(row.activeRn()).isEqualTo(4L);
        assertThat(row.suspendedRn()).isEqualTo(1L);
        assertThat(row.withdrawnRn()).isEqualTo(1L);
        assertThat(row.registrationRate()).isEqualTo(40.0);
        // IN_PROCESSING RNs are ignored by design — they aren't yet issued.
    }

    @Test
    void bpso_surfacesOrphanCountiesUnderOtherBucket_andTotalsRemainEqualToSumOfRows() {
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
        when(subjectRepository.countByActiveTrue()).thenReturn(20L);

        BpsoResponse res = service.bpso();

        assertThat(res.counties()).hasSize(2);
        assertThat(res.counties())
                .extracting(CountyBpsoDto::countyId)
                .containsExactlyInAnyOrder("1", StatisticsService.OTHER_COUNTY_ID);

        long sumActive = res.counties().stream().mapToLong(CountyBpsoDto::activeRn).sum();
        assertThat(res.totals().totalObjects()).isEqualTo(20L);
        assertThat(res.totals().totalRn()).isEqualTo(sumActive).isEqualTo(2L);
    }

    @Test
    void bpso_sortsByAccommodationsDescThenByName() {
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
        when(subjectRepository.countByActiveTrue()).thenReturn(0L);

        BpsoResponse res = service.bpso();

        assertThat(res.counties())
                .extracting(CountyBpsoDto::countyName)
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

}
