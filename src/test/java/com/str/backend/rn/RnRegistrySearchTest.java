package com.str.backend.rn;

import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.rn.dto.RnSummaryDto;
import com.str.backend.str.RnFacilityVerification;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Granice i greške registra — ostalo pokriva {@code RnRegistrySearchIntegrationTest}. */
class RnRegistrySearchTest {

    private final RnService rnService = mock(RnService.class);
    private final RnFacilityVerification facilityVerification = mock(RnFacilityVerification.class);
    private final RnRegistrySearch search = new RnRegistrySearch(rnService, facilityVerification);

    /** Preko granice lista više ne stane u jedan upit (32.767 JDBC parametara) — traži se uži filtar. */
    @Test
    void tooManyVerifiedObjects_asksForNarrowerFilters() {
        Set<String> verified = IntStream.rangeClosed(1, RnRegistrySearch.MAX_VERIFIED_FACILITIES + 1)
                .mapToObj(String::valueOf).collect(Collectors.toSet());
        when(rnService.registryFacilityIds(any())).thenReturn(List.copyOf(verified));
        when(facilityVerification.verifiedFacilityIds(any())).thenReturn(verified);

        assertThatThrownBy(() -> search.search(filter(true), PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.registry.verified.too.many");
        verify(rnService, never()).searchRegistry(any(), anySet(), any());
    }

    @Test
    void withoutVerifiedFilter_eTurizamIsNotAskedForTheWholeRegistry() {
        when(rnService.searchRegistry(any(), anySet(), any())).thenReturn(Page.empty());

        search.search(filter(null), PageRequest.of(0, 20));

        verify(rnService, never()).registryFacilityIds(any());
    }

    /** Prevelik izvoz se odbija prije stupca „Verificiran" — eTurizam se ne pita uzalud. */
    @Test
    void exportOverLimit_isRejectedBeforeAskingETurizam() {
        RnSummaryDto row = new RnSummaryDto("HR1", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "10", false, null);
        when(rnService.searchRegistryForExport(any(), anySet(), eq(2))).thenReturn(List.of(row, row, row));

        assertThatThrownBy(() -> search.searchForExport(filter(null), 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.export.too.many.rows");
        verifyNoInteractions(facilityVerification);
    }

    /** Pad vlastite baze nije nedostupan eTurizam — ne smije postati 503 s njegovim imenom. */
    @Test
    void ownDatabaseFailure_isNotReportedAsETurizam() {
        when(rnService.registryFacilityIds(any()))
                .thenThrow(new DataAccessResourceFailureException("str_rn nedostupan"));

        assertThatThrownBy(() -> search.search(filter(true), PageRequest.of(0, 20)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(facilityVerification);
    }

    @Test
    void eTurizamFailure_withVerifiedFilter_is503() {
        when(rnService.registryFacilityIds(any())).thenReturn(List.of("10"));
        when(facilityVerification.verifiedFacilityIds(any()))
                .thenThrow(new DataAccessResourceFailureException("str nedostupan"));

        assertThatThrownBy(() -> search.search(filter(true), PageRequest.of(0, 20)))
                .isInstanceOf(ExternalRegistryException.class);
    }

    private static RnRegistryFilter filter(Boolean verified) {
        return new RnRegistryFilter(RnRegistryView.ALL, null, null, null, null, false, null, null,
                null, null, null, null, null, verified);
    }
}
