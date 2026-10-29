package com.str.backend.statistics;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.auth.internal.CountyScope;
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

    private static final CountyScope WHOLE_COUNTRY = CountyScope.all(CountyScope.Outcome.NOT_OFFICIAL);

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

        StrResponse res = service.str(null, null, WHOLE_COUNTRY);

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
                rnCount("Splitsko-dalmatinska županija", RnStatus.ACTIVE, 3L),
                rnCount("Splitsko-dalmatinska županija", RnStatus.SUSPENSION_PROPOSED, 1L),
                rnCount("Splitsko-dalmatinska županija", RnStatus.SUSPENDED, 1L),
                rnCount("Splitsko-dalmatinska županija", RnStatus.REVOKED, 2L),
                rnCount("Splitsko-dalmatinska županija", RnStatus.WITHDRAWN, 1L)
        ));
        when(facilityRepository.countByActiveTrue()).thenReturn(10L);

        StrResponse res = service.str(null, null, WHOLE_COUNTRY);

        CountyStrDto row = res.counties().get(0);
        assertThat(row.accommodations()).isEqualTo(10L);
        // A proposed suspension is still a standing RN, so it lands in the active column.
        // Dropping it would lose the RN from every column and from totalRn, which sums the four.
        assertThat(row.activeRn()).isEqualTo(4L);
        assertThat(row.suspendedRn()).isEqualTo(1L);
        // Opozvani (iznajmljivač) i povučeni (službenik) su zasebni stupci.
        assertThat(row.revokedRn()).isEqualTo(2L);
        assertThat(row.withdrawnRn()).isEqualTo(1L);
        assertThat(res.totals().totalRn()).isEqualTo(8L);
        assertThat(row.registrationRate()).isEqualTo(40.0);
    }

    /** Totals must not silently lose a status; the sum of the columns is {@code totalRn}. */
    @Test
    void str_countsProposedSuspensionsTowardsTotals() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Istarska županija")
        ));
        when(accommodationRepository.countByCounty()).thenReturn(List.of(
                accCount("Istarska županija", 4L)
        ));
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of(
                rnCount("Istarska županija", RnStatus.SUSPENSION_PROPOSED, 2L)
        ));
        when(facilityRepository.countByActiveTrue()).thenReturn(4L);

        StrResponse res = service.str(null, null, WHOLE_COUNTRY);

        assertThat(res.counties().get(0).activeRn()).isEqualTo(2L);
        assertThat(res.totals().totalRn()).isEqualTo(2L);
        assertThat(res.counties().get(0).registrationRate()).isEqualTo(50.0);
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

        StrResponse res = service.str(null, null, WHOLE_COUNTRY);

        assertThat(res.counties()).hasSize(2);
        assertThat(res.counties())
                .extracting(CountyStrDto::countyId)
                .containsExactlyInAnyOrder("1", StatisticsService.OTHER_COUNTY_ID);

        long sumActive = res.counties().stream().mapToLong(CountyStrDto::activeRn).sum();
        assertThat(res.totals().totalObjects()).isEqualTo(20L);
        assertThat(res.totals().totalRn()).isEqualTo(sumActive).isEqualTo(2L);
    }

    /**
     * Varijante naziva iste županije (bez nastavka, velikim slovima) idu u njezin red, ne u „Ostalo"
     * — isto kao u prikazu županijskog službenika, pa obojica vide isti broj.
     */
    @Test
    void str_nameVariantsCountTowardsTheirCounty_notOther() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Splitsko-dalmatinska županija")
        ));
        when(accommodationRepository.countByCounty()).thenReturn(List.of(
                accCount("Splitsko-dalmatinska županija", 3L),
                accCount("Splitsko-dalmatinska", 2L),
                accCount("SPLITSKO-DALMATINSKA ŽUPANIJA", 1L)
        ));
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of(
                rnCount("Splitsko-dalmatinska županija", RnStatus.ACTIVE, 2L),
                rnCount("Splitsko-dalmatinska", RnStatus.ACTIVE, 1L),
                rnCount("SPLITSKO-DALMATINSKA ŽUPANIJA", RnStatus.SUSPENDED, 1L)
        ));
        when(facilityRepository.countByActiveTrue()).thenReturn(10L);

        StrResponse res = service.str(null, null, WHOLE_COUNTRY);

        assertThat(res.counties()).singleElement().satisfies(c -> {
            assertThat(c.countyId()).isEqualTo("1");
            assertThat(c.accommodations()).isEqualTo(6L);
            assertThat(c.activeRn()).isEqualTo(3L);
            assertThat(c.suspendedRn()).isEqualTo(1L);
        });

        StrResponse county = service.str(null, null,
                CountyScope.county("Splitsko-dalmatinska županija", 1L, 17, "Splitsko-dalmatinska županija"));
        assertThat(county.counties().get(0).accommodations()).isEqualTo(res.counties().get(0).accommodations());
        assertThat(county.counties().get(0).activeRn()).isEqualTo(res.counties().get(0).activeRn());
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

        StrResponse res = service.str(null, null, WHOLE_COUNTRY);

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

        StrResponse res = service.str(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), WHOLE_COUNTRY);

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

        StrResponse res = service.str(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), WHOLE_COUNTRY);

        assertThat(res.counties()).hasSize(1);
        assertThat(res.counties().get(0).accommodations()).isZero();
        assertThat(res.totals().totalObjects()).isZero();
        assertThat(res.totals().totalRn()).isZero();
    }

    // ── opseg službenika (županija) ─────────────────────────────────────────

    private static final CountyScope SPLIT =
            CountyScope.county("Splitsko-dalmatinska županija", 1L, 17, "Splitsko-dalmatinska županija");

    /**
     * Županijski službenik vidi samo red svoje županije: zbrojeni svi zapisani oblici naziva, bez
     * retka „Ostalo", a ukupno objekata su aktivni eTurizam objekti te županije, ne cijele RH.
     */
    @Test
    void str_restricted_returnsOnlyOwnCountyRow_summingNameVariants() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Splitsko-dalmatinska županija"),
                county(2L, "Grad Zagreb")
        ));
        when(accommodationRepository.countByCounty()).thenReturn(List.of(
                accCount("Splitsko-dalmatinska županija", 6L),
                accCount("SPLITSKO-DALMATINSKA", 4L),
                accCount("Grad Zagreb", 50L),
                accCount("Nepoznata županija", 5L)
        ));
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of(
                rnCount("Splitsko-dalmatinska županija", RnStatus.ACTIVE, 3L),
                rnCount("Splitsko-dalmatinska", RnStatus.SUSPENDED, 1L),
                rnCount("Grad Zagreb", RnStatus.ACTIVE, 30L),
                rnCount("Nepoznata županija", RnStatus.ACTIVE, 2L)
        ));
        when(facilityRepository.countActiveByCounty()).thenReturn(List.of(
                facilityCount("Splitsko-dalmatinska županija", 15L),
                facilityCount("SPLITSKO-DALMATINSKA ŽUPANIJA", 5L),
                facilityCount("Grad Zagreb", 100L),
                facilityCount(null, 7L)
        ));

        StrResponse res = service.str(null, null, SPLIT);

        assertThat(res.counties()).singleElement().satisfies(row -> {
            assertThat(row.countyId()).isEqualTo("1");
            assertThat(row.countyName()).isEqualTo("Splitsko-dalmatinska županija");
            assertThat(row.accommodations()).isEqualTo(10L);
            assertThat(row.activeRn()).isEqualTo(3L);
            assertThat(row.suspendedRn()).isEqualTo(1L);
            // Stopa retka su aktivni RB-ovi prema smještajima, kao u retcima za cijelu RH.
            assertThat(row.registrationRate()).isEqualTo(30.0);
        });
        assertThat(res.counties()).extracting(CountyStrDto::countyId)
                .doesNotContain(StatisticsService.OTHER_COUNTY_ID);
        assertThat(res.totals().totalObjects()).isEqualTo(20L);
        assertThat(res.totals().totalRn()).isEqualTo(4L);
        assertThat(res.totals().coverageRate()).isEqualTo(20.0);
        verify(facilityRepository, never()).countByActiveTrue();
    }

    /** S razdobljem je nazivnik broj smještaja iz razdoblja, kao i bez opsega. */
    @Test
    void str_restricted_withDateFilter_usesPeriodAccommodations() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(county(1L, "Splitsko-dalmatinska županija")));
        when(rnRepository.countDistinctAccommodationsByCountyBetween(from, to)).thenReturn(List.of(
                rnAccCount("Splitsko-dalmatinska županija", 2L),
                rnAccCount("Grad Zagreb", 9L)
        ));
        when(rnRepository.countByCountyAndStatusBetween(from, to)).thenReturn(List.of(
                rnCount("Splitsko-dalmatinska županija", RnStatus.ACTIVE, 2L),
                rnCount("Grad Zagreb", RnStatus.ACTIVE, 9L)
        ));

        StrResponse res = service.str(from, to, SPLIT);

        assertThat(res.counties()).singleElement()
                .satisfies(row -> assertThat(row.accommodations()).isEqualTo(2L));
        assertThat(res.totals().totalObjects()).isEqualTo(2L);
        assertThat(res.totals().totalRn()).isEqualTo(2L);
        verify(facilityRepository, never()).countActiveByCounty();
    }

    /** Bez ograničenja (ministarstvo, nijedno tijelo) opseg ne mijenja ništa. */
    @Test
    void str_unrestrictedScope_isWholeCountry() {
        when(countyRepository.findAllByOrderByZuRb()).thenReturn(List.of(
                county(1L, "Splitsko-dalmatinska županija"),
                county(2L, "Grad Zagreb")
        ));
        when(accommodationRepository.countByCounty()).thenReturn(List.of());
        when(rnRepository.countByCountyAndStatus()).thenReturn(List.of());
        when(facilityRepository.countByActiveTrue()).thenReturn(30L);

        StrResponse res = service.str(null, null, CountyScope.ministry("Ministarstvo turizma i sporta"));

        assertThat(res.counties()).hasSize(2);
        assertThat(res.totals().totalObjects()).isEqualTo(30L);
        verify(facilityRepository, never()).countActiveByCounty();
    }

    private static StrFacilityRepository.CountyCount facilityCount(String county, long count) {
        return new StrFacilityRepository.CountyCount() {
            @Override public String getCounty() { return county; }
            @Override public long getCount() { return count; }
        };
    }
}
