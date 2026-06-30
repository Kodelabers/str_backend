package com.str.backend.statistics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
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
        when(exportService.generatePlatformActivitiesXlsx(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/platform-activities/xlsx").param("county", "Grad Zagreb"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void csv_returnsCsv() throws Exception {
        when(exportService.generatePlatformActivitiesCsv(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/platform-activities/csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"));
    }
}
