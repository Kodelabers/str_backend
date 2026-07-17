package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.dto.RnDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RnDocumentServiceTest {

    private static final String RN = "HR120001000000000001";

    private RnRepository rnRepository;
    private RnDocumentService service;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        service = new RnDocumentService(rnRepository);
    }

    @ParameterizedTest
    @EnumSource(RnDocumentType.class)
    void generate_producesPdf_forEachType(RnDocumentType type) {
        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(detail()));

        byte[] pdf = service.generate(RN, type, "Priloženi dokument istekao je 15.01.2026.");

        assertThat(pdf).isNotEmpty();
        // PDF files begin with the "%PDF" magic bytes.
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void generate_nullReason_doesNotFail() {
        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(detail()));

        byte[] pdf = service.generate(RN, RnDocumentType.NALOG_SUSPENZIJA, null);

        assertThat(pdf).isNotEmpty();
    }

    @Test
    void generate_throwsNotFound_whenRnMissing() {
        when(rnRepository.findDetail(RN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(RN, RnDocumentType.DOPIS_NAMJERE, "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private RnDetailDto detail() {
        return new RnDetailDto(
                RN, RnStatus.ACTIVE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), null, null,
                Instant.now(), Instant.now(), UUID.randomUUID(),
                UUID.randomUUID(), "Primorsko-goranska županija", "Rijeka", "Rijeka", "Korzo", "2",
                "Apartman More", "Apartman", 4, 8, "3 zvjezdice",
                UUID.randomUUID(), "Ivan", "Ivić", null, "ivan@example.hr", "12345678901",
                false, null, null, null,
                null, null, null, null, null);
    }
}
