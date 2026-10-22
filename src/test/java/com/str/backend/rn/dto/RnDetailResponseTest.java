package com.str.backend.rn.dto;

import com.str.backend.domain.RnStatus;
import com.str.backend.rn.dto.RnDetailResponse.LessorAddressKind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RnDetailResponseTest {

    private static final RnDetailExtras EXTRAS =
            new RnDetailExtras(null, null, null, null, null, null, null, null, null, null);

    @Test
    void naturalPerson_addressIsResidence() {
        assertThat(RnDetailResponse.of(detail(UUID.randomUUID(), false, "12312312316"), EXTRAS).lessorAddressKind())
                .isEqualTo(LessorAddressKind.RESIDENCE);
    }

    /** e-Zastupanja: OIB tvrtke, a u stupcima adrese sjedište iz OIB sustava. */
    @Test
    void niasLegalEntity_addressIsSeat() {
        assertThat(RnDetailResponse.of(detail(UUID.randomUUID(), true, "33333333360"), EXTRAS).lessorAddressKind())
                .isEqualTo(LessorAddressKind.SEAT);
    }

    /** Non-EU pravna osoba: sjedište je legalEntityCity/Country, adresa je stalna adresa osobe. */
    @Test
    void nonEuLegalEntity_addressIsResidence() {
        assertThat(RnDetailResponse.of(detail(UUID.randomUUID(), true, null), EXTRAS).lessorAddressKind())
                .isEqualTo(LessorAddressKind.RESIDENCE);
    }

    @Test
    void unknownLessor_hasNoAddressKind() {
        assertThat(RnDetailResponse.of(detail(null, null, null), EXTRAS).lessorAddressKind()).isNull();
    }

    /** Oba recorda se razmotavaju u isti JSON objekt — isto ime komponente dalo bi dupli ključ. */
    @Test
    void unwrappedRecords_haveDisjointComponentNames() {
        Set<String> detail = names(RnDetailDto.class);
        Set<String> extras = names(RnDetailExtras.class);

        assertThat(detail).doesNotContainAnyElementsOf(extras).doesNotContain("lessorAddressKind");
        assertThat(extras).doesNotContain("lessorAddressKind");
    }

    private static Set<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet());
    }

    private static RnDetailDto detail(UUID lessorId, Boolean legalEntityOwner, String lessorOib) {
        return new RnDetailDto("HR000000000000000001", RnStatus.ACTIVE, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                lessorId, null, null, null, null, lessorOib,
                legalEntityOwner, null, null, null,
                null, null, null, null, null);
    }
}
