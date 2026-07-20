package com.str.backend.statistics;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(StatisticsController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlatformActivitiesExportControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private StatisticsService service;
    @MockBean private PlatformActivityQuery platformActivityQuery;
    @MockBean private StatisticsExportService exportService;

    @Test
    void xlsx_returnsSpreadsheet() throws Exception {
        when(exportService.generatePlatformActivitiesXlsx(any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/platform-activities/xlsx").param("county", "Grad Zagreb"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void csv_returnsCsv() throws Exception {
        when(exportService.generatePlatformActivitiesCsv(any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/platform-activities/csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"));
    }

    /** Spec §2.10: clicking the anomaly counter must reach the query as an active filter. */
    @Test
    void list_passesAnomaliesOnlyAndGuestCountryToQuery() throws Exception {
        mvc.perform(get("/api/statistics/platform-activities")
                        .param("anomaliesOnly", "true")
                        .param("guestCountry", "Njemačka")
                        .param("platformId", "2"))
                .andExpect(status().isOk());

        PlatformActivityFilter filter = captureListFilter();
        assertThat(filter.anomaliesOnly()).isTrue();
        assertThat(filter.guestCountry()).isEqualTo("Njemačka");
        assertThat(filter.platformId()).isEqualTo(2L);
    }

    /** Both filters are opt-in — an unfiltered request must not silently hide rows. */
    @Test
    void list_defaultsToNoAnomalyFilterAndNoGuestCountry() throws Exception {
        mvc.perform(get("/api/statistics/platform-activities")).andExpect(status().isOk());

        PlatformActivityFilter filter = captureListFilter();
        assertThat(filter.anomaliesOnly()).isFalse();
        assertThat(filter.guestCountry()).isNull();
    }

    /**
     * The criteria are bound straight into the record, so the whole set — dates and the numeric
     * id included — has to survive that binding, not just the two string filters.
     */
    @Test
    void list_bindsEveryCriterionIntoTheFilter() throws Exception {
        mvc.perform(get("/api/statistics/platform-activities")
                        .param("platformId", "3")
                        .param("od", "2026-01-01")
                        .param("toDate", "2026-03-31")
                        .param("county", "Istarska županija")
                        .param("status", "aktivan")
                        .param("q", "Korzo")
                        .param("rn", "HR010001102030405060")
                        .param("anomaliesOnly", "true")
                        .param("guestCountry", "Italija"))
                .andExpect(status().isOk());

        PlatformActivityFilter f = captureListFilter();
        assertThat(f.platformId()).isEqualTo(3L);
        assertThat(f.od()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(f.toDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(f.county()).isEqualTo("Istarska županija");
        assertThat(f.status()).isEqualTo("aktivan");
        assertThat(f.q()).isEqualTo("Korzo");
        assertThat(f.rn()).isEqualTo("HR010001102030405060");
        assertThat(f.anomaliesOnly()).isTrue();
        assertThat(f.guestCountry()).isEqualTo("Italija");
    }

    /**
     * A malformed date must be rejected, not silently dropped into an unfiltered query — binding
     * into the record must not turn a bad value into "no filter".
     */
    @Test
    void list_rejectsMalformedDate() throws Exception {
        mvc.perform(get("/api/statistics/platform-activities").param("od", "01.01.2026."))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentNotValidException.class));

        verifyNoInteractions(platformActivityQuery);
    }

    /** The exports must honour the same criteria as the list, or the file contradicts the screen. */
    @Test
    void csv_passesAnomaliesOnlyToExport() throws Exception {
        when(exportService.generatePlatformActivitiesCsv(any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/platform-activities/csv")
                        .param("anomaliesOnly", "true")
                        .param("guestCountry", "Italija"))
                .andExpect(status().isOk());

        ArgumentCaptor<PlatformActivityFilter> captor =
                ArgumentCaptor.forClass(PlatformActivityFilter.class);
        verify(exportService).generatePlatformActivitiesCsv(captor.capture());
        assertThat(captor.getValue().anomaliesOnly()).isTrue();
        assertThat(captor.getValue().guestCountry()).isEqualTo("Italija");
    }

    private PlatformActivityFilter captureListFilter() {
        ArgumentCaptor<PlatformActivityFilter> captor =
                ArgumentCaptor.forClass(PlatformActivityFilter.class);
        verify(platformActivityQuery).query(captor.capture(), anyInt(), anyInt());
        return captor.getValue();
    }
}
