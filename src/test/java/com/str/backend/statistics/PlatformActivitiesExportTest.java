package com.str.backend.statistics;

import com.str.backend.statistics.dto.PlatformActivityRowDto;
import com.str.backend.statistics.dto.PlatformChipDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test of the platform-activities export formatting (STR-3.2). The native SQL query
 * (Postgres JSON_AGG) is mocked, so this exercises the XLSX/CSV writers without a DB.
 */
class PlatformActivitiesExportTest {

    private PlatformActivityQuery query;
    private StatisticsExportService service;

    @BeforeEach
    void setUp() {
        query = mock(PlatformActivityQuery.class);
        service = new StatisticsExportService(
                mock(StatisticsService.class), mock(StatisticsRepository.class), query);

        PlatformActivityRowDto row = new PlatformActivityRowDto(
                "id1", "HR120001000000000001", "Ivan Ivić", "Korzo 2", "Rijeka",
                "PGŽ", "Primorsko-goranska županija",
                List.of(new PlatformChipDto("1", "Booking"), new PlatformChipDto("2", "Airbnb")),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 10L, 4L,
                "aktivan", Instant.parse("2026-04-01T10:00:00Z"));
        when(query.queryAll(any())).thenReturn(List.of(row));
    }

    @Test
    void csv_containsRowData() {
        byte[] csv = service.generatePlatformActivitiesCsv(PlatformActivityFilter.none());
        String text = new String(csv, StandardCharsets.UTF_8);

        assertThat(text).contains("HR120001000000000001");
        assertThat(text).contains("Booking, Airbnb");
        assertThat(text).contains("Aktivan");
        assertThat(text).contains("Primorsko-goranska županija");
    }

    @Test
    void xlsx_isNonEmptyZip() {
        byte[] xlsx = service.generatePlatformActivitiesXlsx(PlatformActivityFilter.none());

        assertThat(xlsx).isNotEmpty();
        // XLSX is a ZIP container — starts with the "PK" local-file-header signature.
        assertThat(xlsx[0]).isEqualTo((byte) 0x50);
        assertThat(xlsx[1]).isEqualTo((byte) 0x4B);
    }
}
