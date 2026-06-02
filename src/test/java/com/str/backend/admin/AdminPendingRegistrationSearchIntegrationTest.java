package com.str.backend.admin;

import com.str.backend.lessor.LessorDocumentEntity;
import com.str.backend.lessor.LessorDocumentRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Megasearch + per-field filter acceptance for GET /api/admin/pending-registrations and its
 * xlsx export (BACKEND_SEARCH_SPEC §2). Asserts the export applies identical filtering.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPendingRegistrationSearchIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private LessorDocumentRepository documentRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        documentRepository.deleteAll();
        lessorRepository.deleteAll();
        jdbc.update("DELETE FROM str.country");
        jdbc.update("INSERT INTO str.country (id, name, iso2_alpha, active) VALUES (?,?,?,?)",
                1L, "Njemačka", "DE", true);
        jdbc.update("INSERT INTO str.country (id, name, iso2_alpha, active) VALUES (?,?,?,?)",
                2L, "Austrija", "AT", true);

        // P1
        LessorEntity ana = lessorRepository.save(LessorEntity.createNonEuRegistration(
                "Ana", "Anić", "Hauptstrasse 1", "ana@example.com", "ana", "x",
                LocalDate.of(1990, 1, 1), 1, "12345678901", "+49100"));
        document(ana.getLessorId(), "PASSPORT", "P123456");

        // P2
        LessorEntity boris = lessorRepository.save(LessorEntity.createNonEuRegistration(
                "Boris", "Borić", "Ringstrasse 2", "boris@test.hr", "boris", "x",
                LocalDate.of(1985, 6, 6), 2, "98765432109", "+43100"));
        document(boris.getLessorId(), "ID_CARD", "ID999");
    }

    private void document(UUID lessorId, String type, String number) {
        documentRepository.save(LessorDocumentEntity.create(lessorId, type, number,
                new byte[]{1}, null));
    }

    @Test
    void tokenAnd_matchesNameTokens() throws Exception {
        mvc.perform(get("/api/admin/pending-registrations").param("q", "ana anić"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].lastName").value("Anić"));
    }

    @Test
    void tokenAnd_oneTokenInNoField_yieldsNoMatch() throws Exception {
        mvc.perform(get("/api/admin/pending-registrations").param("q", "ana split"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void q_matchesCountryName() throws Exception {
        mvc.perform(get("/api/admin/pending-registrations").param("q", "njemačka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].lastName").value("Anić"));
    }

    @Test
    void perFieldFilters_eachNarrowCorrectly() throws Exception {
        expectOne(get("/api/admin/pending-registrations").param("email", "test.hr"), "Borić");
        expectOne(get("/api/admin/pending-registrations").param("taxNumber", "12345"), "Anić");
        expectOne(get("/api/admin/pending-registrations").param("documentNumber", "ID999"), "Borić");
        expectOne(get("/api/admin/pending-registrations").param("name", "borić"), "Borić");
        expectOne(get("/api/admin/pending-registrations").param("country", "njem"), "Anić");
        expectOne(get("/api/admin/pending-registrations").param("documentType", "PASSPORT"), "Anić");
    }

    private void expectOne(MockHttpServletRequestBuilder req, String lastName) throws Exception {
        mvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].lastName").value(lastName));
    }

    @Test
    void defaultStatusPending_returnsAll() throws Exception {
        mvc.perform(get("/api/admin/pending-registrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void export_appliesIdenticalFiltering() throws Exception {
        // List with a filter → 1 row; export with the same filter → 1 data row.
        mvc.perform(get("/api/admin/pending-registrations").param("documentType", "PASSPORT"))
                .andExpect(jsonPath("$.totalElements").value(1));

        byte[] xlsx = mvc.perform(get("/api/admin/pending-registrations/export/xlsx")
                        .param("documentType", "PASSPORT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            // header is row 0; getLastRowNum() == number of data rows
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
        }
    }
}
