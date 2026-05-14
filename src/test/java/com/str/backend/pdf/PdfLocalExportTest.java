package com.str.backend.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.str.StrLessorLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Generira registracijski broj i sprema generirani PDF lokalno u target/test-pdf/.
 * Namijenjen vizualnoj provjeri izgleda dokumenta bez stvarnog slanja na eGOP.
 *
 * Pokretanje: mvn test -Dtest="PdfLocalExportTest"
 * Izlaz:      target/test-pdf/zahtjev-<timestamp>.pdf
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PdfLocalExportTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private SubmissionRepository submissionRepository;

    @MockBean private StrLessorLookupService strLessorLookupService;
    @MockBean private CountyRepository countyRepository;

    @BeforeEach
    void setupMocks() {
        LessorEntity lessor = LessorEntity.create(
                "Pero", "Perić",
                "Sjenjak", "19", "Osijek", "Osječko-baranjska županija",
                "pero.peric@example.hr");
        setField(lessor, "lessorOib", "12312312316");
        lessor.setContact("Pero Perić", "031-555-100", "091-555-100", null);

        when(strLessorLookupService.resolveLessor(anyString())).thenReturn(lessor);

        CountyEntity county = buildCounty(7L, "Osječko-baranjska županija");
        when(countyRepository.findById(7L)).thenReturn(Optional.of(county));
    }

    @Test
    void generate_registration_number_and_save_pdf_locally() throws Exception {
        MvcResult postResult = mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(buildRequest())))
                .andExpect(status().isCreated())
                .andReturn();

        ObjectNode body = (ObjectNode) om.readTree(postResult.getResponse().getContentAsByteArray());
        UUID lessorId = UUID.fromString(body.get("lessorId").asText());
        String rn = body.get("assignedRbs").get(0).get("rn").asText();
        UUID submissionId = UUID.fromString(body.get("submissionId").asText());

        MvcResult pdfResult = mvc.perform(
                        get("/api/generateRegistrationNumber/{id}/pdf", submissionId)
                                .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk())
                .andReturn();

        byte[] pdf = pdfResult.getResponse().getContentAsByteArray();
        assertThat(pdf).startsWith(new byte[]{'%', 'P', 'D', 'F'});

        Path outDir = Paths.get("target", "test-pdf");
        Files.createDirectories(outDir);
        String filename = "zahtjev-" + LocalDateTime.now().format(TS) + ".pdf";
        Path outFile = outDir.resolve(filename);
        Files.write(outFile, pdf);

        System.out.println("\n========================================");
        System.out.println("  PDF spreman: " + outFile.toAbsolutePath());
        System.out.println("  Registracijski broj: " + rn);
        System.out.println("  Submission ID: " + submissionId);
        System.out.println("========================================\n");

        SubmissionEntity submission = submissionRepository.findById(submissionId).orElseThrow();
        assertThat(submission.getFilingNumber()).isNotBlank();
        assertThat(pdf.length).isGreaterThan(1000);
    }

    private RegistrationRequest buildRequest() {
        RegistrationRequest req = new RegistrationRequest();
        req.setOib("12312312316");
        req.setName("AP1 Dugi Rat");
        req.setTypeId("1");
        req.setCountyId(7L);
        req.setCityId("Dugi Rat");
        req.setSettlementId("Dugi Rat");
        req.setStreet("Drage Ivaniševića");
        req.setStreetNumber("3");
        req.setPostalCode("21315");
        req.setMaxBeds(4);
        req.setMaxGuests(6);
        req.setOfferType(OfferType.RESIDENCE);
        return req;
    }

    private CountyEntity buildCounty(Long id, String name) {
        try {
            var ctor = CountyEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CountyEntity c = ctor.newInstance();
            setField(c, "id", id);
            setField(c, "name", name);
            setField(c, "active", true);
            return c;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set field " + name, e);
        }
    }
}
