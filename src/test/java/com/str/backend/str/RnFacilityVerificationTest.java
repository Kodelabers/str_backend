package com.str.backend.str;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.str.backend.domain.RnStatus;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.str.StrFacilityRepository.FacilityVerificationRow;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Stupac „Verificiran" na „Mojim registracijskim brojevima" (NIAS). */
class RnFacilityVerificationTest {

    private final StrFacilityRepository facilityRepository = mock(StrFacilityRepository.class);
    private final RnFacilityVerification verification = new RnFacilityVerification(facilityRepository);

    @Test
    void existingFacility_takesVerificationFromETurizam() {
        when(facilityRepository.findObjectVerification(any())).thenReturn(List.of(
                row(10L, true), row(11L, false)));

        List<LessorRnSummaryDto> result = verification.withFacilityVerified(List.of(
                summary("HR1", "10"), summary("HR2", "11")));

        assertThat(result).extracting(LessorRnSummaryDto::facilityVerified).containsExactly(true, false);
        verify(facilityRepository).findObjectVerification(List.of(10L, 11L));
    }

    /** Odluka 7. 10. 2026.: RB novog objekta nije verificiran — objekta u eTurizmu nema. */
    @Test
    void newFacility_isNotVerified_withoutQueryingETurizam() {
        List<LessorRnSummaryDto> result = verification.withFacilityVerified(List.of(
                summary("HR1", null), summary("HR2", "  ")));

        assertThat(result).extracting(LessorRnSummaryDto::facilityVerified).containsExactly(false, false);
        verifyNoInteractions(facilityRepository);
    }

    @Test
    void facilityMissingInETurizam_isNotVerified() {
        when(facilityRepository.findObjectVerification(any())).thenReturn(List.of());

        List<LessorRnSummaryDto> result = verification.withFacilityVerified(List.of(summary("HR1", "10")));

        assertThat(result.getFirst().facilityVerified()).isFalse();
    }

    @Test
    void nonNumericFacilityId_isTreatedAsNoFacility() {
        List<LessorRnSummaryDto> result = verification.withFacilityVerified(List.of(summary("HR1", "abc")));

        assertThat(result.getFirst().facilityVerified()).isFalse();
        verifyNoInteractions(facilityRepository);
    }

    /** Kvar eTurizma ne ruši popis: brojevi se vraćaju bez stupca (frontend prikazuje „-"). */
    @Test
    void eTurizamFailure_returnsRowsWithoutVerification() {
        when(facilityRepository.findObjectVerification(any()))
                .thenThrow(new DataAccessResourceFailureException("str nedostupan"));

        List<LessorRnSummaryDto> result = verification.withFacilityVerified(List.of(
                summary("HR1", "10"), summary("HR2", null)));

        assertThat(result).extracting(LessorRnSummaryDto::rn).containsExactly("HR1", "HR2");
        assertThat(result).extracting(LessorRnSummaryDto::facilityVerified).containsOnlyNulls();
    }

    /** {@code facilityId} služi samo izračunu — u odgovor ide samo {@code facilityVerified}. */
    @Test
    void json_carriesVerifiedFlag_butNotFacilityId() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

        String json = mapper.writeValueAsString(summary("HR1", "10").withFacilityVerified(true));

        assertThat(json).contains("\"facilityVerified\":true").doesNotContain("facilityId");
    }

    private static LessorRnSummaryDto summary(String rn, String facilityId) {
        return new LessorRnSummaryDto(rn, RnStatus.ACTIVE, LocalDate.of(2026, 10, 7),
                "Apartman", "Ilica", "1", "Zagreb", "Apartman", false, null, facilityId);
    }

    private static FacilityVerificationRow row(Long facilityId, Boolean verified) {
        return new FacilityVerificationRow() {
            @Override public Long getFacilityId() { return facilityId; }
            @Override public Boolean getVerified() { return verified; }
        };
    }
}
