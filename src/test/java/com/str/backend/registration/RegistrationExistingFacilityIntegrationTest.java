package com.str.backend.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end za postojeći eTurizam objekt kojem eTurizam ne zna dio podataka: obrazac ih šalje
 * kao {@code null}, a RB se mora izdati — kroz validaciju zahtjeva, dopunu iz eTurizma, nullable
 * stupce, GO pipeline, izdavanje i PDF podneska. Provjera vlasništva ({@link FacilityClaimVerifier})
 * je zamijenjena: native upit nad shemom {@code str} H2 nema, a pokrivena je zasebnim testom.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationExistingFacilityIntegrationTest {

    private static final String OIB = "12312312316";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private AccommodationRepository accommodationRepository;

    @MockBean private SubjectProfileService subjectProfileService;
    @MockBean private CountyRepository countyRepository;
    @MockBean private FacilityClaimVerifier facilityClaimVerifier;

    @BeforeEach
    void setUp() {
        when(subjectProfileService.resolveLessor(any(), any(), any(), any())).thenAnswer(inv -> {
            LessorEntity l = LessorEntity.create("PERO", "PERIĆ",
                    "Ilica", "1", "Zagreb", "Grad Zagreb", "pero.peric@example.hr");
            l.setLessorOib((String) inv.getArgument(0));
            return l;
        });
        when(countyRepository.findAll()).thenReturn(List.of(county(18L, "Splitsko-dalmatinska županija")));
    }

    /** eTurizam zna županiju, mjesto, naziv i kapacitet, ali ne ulicu ni kućni broj (tipično). */
    @Test
    void issuesRn_andCompletesKnownDataFromEturizam() throws Exception {
        FacilityOwnershipRow facility = mock(FacilityOwnershipRow.class);
        when(facility.getCountyName()).thenReturn("SPLITSKO-DALMATINSKA");
        when(facility.getMunicipalityName()).thenReturn("MAKARSKA");
        when(facility.getName()).thenReturn("Apartman Marija");
        when(facility.getBeds()).thenReturn(4);
        when(facility.getAuxiliaryBeds()).thenReturn(2);
        when(facilityClaimVerifier.verify(eq(OIB), eq("700001"), any())).thenReturn(Optional.of(facility));

        JsonNode body = submit("700001");

        assertThat(body.get("registrationNumber").asText()).matches("HR18\\d{16}");
        AccommodationEntity saved = accommodationOf("700001");
        assertThat(saved.getCounty()).isEqualTo("Splitsko-dalmatinska županija");
        assertThat(saved.getCity()).isEqualTo("MAKARSKA");
        assertThat(saved.getName()).isEqualTo("Apartman Marija");
        assertThat(saved.getMaxGuests()).isEqualTo(6);
        assertThat(saved.getStreet()).isNull();
        assertThat(saved.getStreetNumber()).isNull();
        assertPdfStored(body);
    }

    /**
     * Ni eTurizam ne zna ništa — RB se svejedno izdaje, samo bez koda županije i vrste. Broj
     * gostiju tada upisuje korisnik (V-2), pa se sprema njegova vrijednost.
     */
    @Test
    void issuesRn_evenWhenEturizamKnowsNothing() throws Exception {
        FacilityOwnershipRow facility = mock(FacilityOwnershipRow.class);
        when(facilityClaimVerifier.verify(eq(OIB), eq("700002"), any())).thenReturn(Optional.of(facility));

        JsonNode body = submit("700002", 4);

        assertThat(body.get("registrationNumber").asText()).matches("HR00\\d{16}");
        AccommodationEntity saved = accommodationOf("700002");
        assertThat(saved.getCounty()).isNull();
        assertThat(saved.getMaxBeds()).isEqualTo(4);
        assertPdfStored(body);
    }

    /** V-2: eTurizam ne zna kapacitet, a korisnik ga nije upisao — RB se ne izdaje. */
    @Test
    void rejects_whenNeitherEturizamNorUserKnowsMaxGuests() throws Exception {
        FacilityOwnershipRow facility = mock(FacilityOwnershipRow.class);
        when(facilityClaimVerifier.verify(eq(OIB), eq("700003"), any())).thenReturn(Optional.of(facility));

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(requestWithoutFacilityData("700003", null))))
                .andExpect(status().isBadRequest());

        assertThat(accommodationRepository.findAll()).noneMatch(a -> "700003".equals(a.getFacilityId()));
    }

    private JsonNode submit(String facilityId) throws Exception {
        return submit(facilityId, null);
    }

    private JsonNode submit(String facilityId, Integer maxBeds) throws Exception {
        byte[] response = mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(requestWithoutFacilityData(facilityId, maxBeds))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();
        return om.readTree(response);
    }

    private AccommodationEntity accommodationOf(String facilityId) {
        return accommodationRepository.findAll().stream()
                .filter(a -> facilityId.equals(a.getFacilityId()))
                .findFirst().orElseThrow();
    }

    private void assertPdfStored(JsonNode body) {
        SubmissionEntity submission = submissionRepository
                .findById(UUID.fromString(body.get("submissionId").asText())).orElseThrow();
        assertThat(submission.getPdfContent()).startsWith(new byte[]{'%', 'P', 'D', 'F'});
    }

    /**
     * Kakav obrazac šalje za postojeći objekt kad ništa od podataka iz eTurizma ne razriješi;
     * {@code maxBeds} je broj gostiju koji je korisnik upisao (ili {@code null}).
     */
    private static RegistrationRequest requestWithoutFacilityData(String facilityId, Integer maxBeds) {
        return new RegistrationRequest(
                OIB, null, null, null, null, null, null, null, null, null, maxBeds,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, "1", false, true,
                null, null, null, null, null, null, facilityId,
                "iznajmljivac@example.com", "0991234567", null, null, null);
    }

    private static CountyEntity county(Long id, String name) {
        try {
            var ctor = CountyEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CountyEntity c = ctor.newInstance();
            for (var entry : Map.of("id", (Object) id, "name", name, "zuRb", id.intValue()).entrySet()) {
                Field f = CountyEntity.class.getDeclaredField(entry.getKey());
                f.setAccessible(true);
                f.set(c, entry.getValue());
            }
            return c;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
