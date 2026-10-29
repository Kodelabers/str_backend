package com.str.backend.statistics;

import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.RnScopeGuard;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
    @MockBean private CountyScopeService countyScope;
    @MockBean private RnScopeGuard scopeGuard;

    @Test
    void xlsx_returnsSpreadsheet() throws Exception {
        when(exportService.generatePlatformActivitiesXlsx(any(), any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/platform-activities/xlsx").param("county", "Grad Zagreb"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void csv_returnsCsv() throws Exception {
        when(exportService.generatePlatformActivitiesCsv(any(), any())).thenReturn(new byte[]{1});

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
        when(exportService.generatePlatformActivitiesCsv(any(), any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/platform-activities/csv")
                        .param("anomaliesOnly", "true")
                        .param("guestCountry", "Italija"))
                .andExpect(status().isOk());

        ArgumentCaptor<PlatformActivityFilter> captor =
                ArgumentCaptor.forClass(PlatformActivityFilter.class);
        verify(exportService).generatePlatformActivitiesCsv(captor.capture(), any());
        assertThat(captor.getValue().anomaliesOnly()).isTrue();
        assertThat(captor.getValue().guestCountry()).isEqualTo("Italija");
    }

    // --- Opseg službenika (županija): popis, izvozi i accordion ---

    private static final List<String> SPLIT_NAMES =
            List.of("Splitsko-dalmatinska županija", "SPLITSKO-DALMATINSKA ŽUPANIJA");

    /** Popis aktivnosti dobiva nazive županije službenika, uz filtre iz zahtjeva. */
    @Test
    void list_countyOfficial_passesScopeCountiesToQuery() throws Exception {
        when(countyScope.accommodationCounties(any())).thenReturn(SPLIT_NAMES);

        mvc.perform(get("/api/statistics/platform-activities").param("county", "Grad Zagreb")
                        .param("page", "2").param("size", "50"))
                .andExpect(status().isOk());

        verify(platformActivityQuery).query(any(PlatformActivityFilter.class), eq(SPLIT_NAMES), eq(2), eq(50));
    }

    /** Izvozi ne smiju dati više od ekrana — isti opseg ide u xlsx i csv. */
    @Test
    void exports_countyOfficial_passScopeCountiesToExport() throws Exception {
        when(countyScope.accommodationCounties(any())).thenReturn(SPLIT_NAMES);
        when(exportService.generatePlatformActivitiesXlsx(any(), any())).thenReturn(new byte[]{1});
        when(exportService.generatePlatformActivitiesCsv(any(), any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/platform-activities/xlsx").param("county", "Grad Zagreb"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/statistics/platform-activities/csv").param("county", "Grad Zagreb"))
                .andExpect(status().isOk());

        verify(exportService).generatePlatformActivitiesXlsx(any(PlatformActivityFilter.class), eq(SPLIT_NAMES));
        verify(exportService).generatePlatformActivitiesCsv(any(PlatformActivityFilter.class), eq(SPLIT_NAMES));
    }

    /** Accordion RB-a iz druge županije je 404, kao detalj RB-a — upit se ne izvršava. */
    @Test
    void breakdown_rnOutsideScope_returns404_andQueryIsNeverCalled() throws Exception {
        String rn = "HR010001102030405060";
        doThrow(new ResourceNotFoundException("rn not found: " + rn))
                .when(scopeGuard).requireInScope(eq(rn), any());

        mvc.perform(get("/api/statistics/platform-activities/breakdown")
                        .param("rn", rn)
                        .param("periodFrom", "2026-01-01")
                        .param("periodTo", "2026-01-31"))
                .andExpect(status().isNotFound());

        verify(platformActivityQuery, never()).breakdown(any(), any(), any());
    }

    @Test
    void breakdown_rnInScope_reachesQuery() throws Exception {
        String rn = "HR010001102030405060";

        mvc.perform(get("/api/statistics/platform-activities/breakdown")
                        .param("rn", rn)
                        .param("periodFrom", "2026-01-01")
                        .param("periodTo", "2026-01-31"))
                .andExpect(status().isOk());

        verify(scopeGuard).requireInScope(eq(rn), any());
        verify(platformActivityQuery).breakdown(rn, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    }

    private PlatformActivityFilter captureListFilter() {
        ArgumentCaptor<PlatformActivityFilter> captor =
                ArgumentCaptor.forClass(PlatformActivityFilter.class);
        verify(platformActivityQuery).query(captor.capture(), any(), anyInt(), anyInt());
        return captor.getValue();
    }
}
