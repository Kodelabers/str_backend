package com.str.backend.objection;

import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.exception.GlobalExceptionHandler;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.RnScopeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wiring kontrolera; logika je u {@link SuspensionObjectionAdminServiceTest}. */
class SuspensionObjectionControllerTest {

    private static final String RN = "HR120001000000000123";
    private static final String BASE = "/api/rn/{rn}/objections";
    private static final String ACTOR = "INTERNAL:12345678903";

    private final SuspensionObjectionAdminService service = mock(SuspensionObjectionAdminService.class);
    private final InternalAccessService internalAccess = mock(InternalAccessService.class);
    private final RnScopeGuard scopeGuard = mock(RnScopeGuard.class);
    private final com.str.backend.rn.RucniAktService rucniAkt = mock(com.str.backend.rn.RucniAktService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Datumi kao ISO tekst, kao u aplikaciji (Spring Boot gasi WRITE_DATES_AS_TIMESTAMPS).
        mvc = MockMvcBuilders.standaloneSetup(new SuspensionObjectionController(service, internalAccess, scopeGuard, rucniAkt))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()),
                        new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        when(internalAccess.actorFor(any())).thenReturn(Optional.of(ACTOR));
    }

    @Test
    void file_pdfIsInline() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.file(RN, id)).thenReturn(new SuspensionObjectionFileDto(
                "prigovor.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.US_ASCII)));

        mvc.perform(get(BASE + "/{id}/file", RN, id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    /** Preglednik Word ne prikazuje — preuzima se, s hrvatskim znakovima u nazivu. */
    @Test
    void file_wordIsAttachment() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.file(RN, id)).thenReturn(new SuspensionObjectionFileDto(
                "prigovor-žalba.docx", SuspensionObjectionService.CONTENT_TYPE_DOCX, new byte[]{0x50, 0x4B}));

        mvc.perform(get(BASE + "/{id}/file", RN, id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition", containsString("UTF-8''prigovor-%C5%BEalba.docx")));
    }

    @Test
    void accept_passesActorFromSessionAndNote() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post(BASE + "/{id}/accept", RN, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Dostavljena suglasnost\"}"))
                .andExpect(status().isNoContent());

        verify(service).accept(RN, id, ACTOR, "Dostavljena suglasnost", null);
    }

    @Test
    void reject_withoutBody_hasNoNote() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post(BASE + "/{id}/reject", RN, id))
                .andExpect(status().isNoContent());

        verify(service).reject(eq(RN), eq(id), eq(ACTOR), isNull(), eq(false));
    }

    /** Neuvažavanje nosi je li službenik županijski (bez KLASE), a 403 iz servisa nosi kod u details.code. */
    @Test
    void reject_passesCountyOfficialFlag_and403CarriesCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(rucniAkt.zupanijskiSluzbenik(any())).thenReturn(true);

        mvc.perform(post(BASE + "/{id}/reject", RN, id)).andExpect(status().isNoContent());
        verify(service).reject(RN, id, ACTOR, null, true);

        when(rucniAkt.zupanijskiSluzbenik(any())).thenReturn(false);
        doThrow(new com.str.backend.exception.ForbiddenException("error.objection.notCountyOfficial",
                "OBJECTION_NOT_COUNTY_OFFICIAL")).when(service).reject(RN, id, ACTOR, null, false);

        mvc.perform(post(BASE + "/{id}/reject", RN, id))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.code").value("OBJECTION_NOT_COUNTY_OFFICIAL"));
    }

    // --- T16: urudžbiranje podneska u županijskom postupku

    @Test
    void register_countyOfficial_returnsObjectionWithRegistration() throws Exception {
        UUID id = UUID.randomUUID();
        com.str.backend.rn.RucniAkt rucni = new com.str.backend.rn.RucniAkt("UP/I-334-06/26-01/7", "2181-26-3",
                "Splitsko-dalmatinska županija", null, null, 17);
        when(rucniAkt.zaRadnju(any(), eq("UP/I-334-06/26-01/7"), eq("2181-26-3"))).thenReturn(rucni);
        when(service.register(RN, id, ACTOR, rucni)).thenReturn(dto(id, new SuspensionObjectionDto.Urudzbiranje(
                "UP/I-334-06/26-01/7", "2181-26-3", java.time.LocalDate.of(2026, 11, 12))));

        mvc.perform(post(BASE + "/{id}/register", RN, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"klasa\":\"UP/I-334-06/26-01/7\",\"urbroj\":\"2181-26-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectionId").value(id.toString()))
                .andExpect(jsonPath("$.zupanijskiPostupak").value(true))
                .andExpect(jsonPath("$.urudzbiranje.klasa").value("UP/I-334-06/26-01/7"))
                .andExpect(jsonPath("$.urudzbiranje.urBroj").value("2181-26-3"))
                .andExpect(jsonPath("$.urudzbiranje.datum").value("2026-11-12"));
    }

    /** Službenik ministarstva (ili neodređen opseg) ne urudžbira ručno: 403 s kodom, servis se ne zove. */
    @Test
    void register_notCountyOfficial_is403() throws Exception {
        UUID id = UUID.randomUUID();
        when(rucniAkt.zaRadnju(any(), any(), any())).thenReturn(null);

        mvc.perform(post(BASE + "/{id}/register", RN, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"klasa\":\"UP/I-334-06/26-01/7\",\"urbroj\":\"2181-26-3\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.code").value("OBJECTION_NOT_COUNTY_OFFICIAL"));

        verifyNoInteractions(service);
    }

    /** Neispravan URBROJ odbija {@link com.str.backend.rn.RucniAkt#provjeri} (kroz zaRadnju): 400. */
    @Test
    void register_invalidNumbers_is400() throws Exception {
        UUID id = UUID.randomUUID();
        when(rucniAkt.zaRadnju(any(), any(), any())).thenAnswer(inv -> {
            com.str.backend.rn.RucniAkt.provjeri(inv.getArgument(1), inv.getArgument(2));
            return null;
        });

        mvc.perform(post(BASE + "/{id}/register", RN, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"klasa\":\"UP/I-334-06/26-01/7\",\"urbroj\":\"2181-26-\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("error.rn.akt.urbroj.format"));

        verifyNoInteractions(service);
    }

    @Test
    void register_conflictFromService_is409WithCode() throws Exception {
        UUID id = UUID.randomUUID();
        com.str.backend.rn.RucniAkt rucni = new com.str.backend.rn.RucniAkt("K", "U-1", null, null, null, 17);
        when(rucniAkt.zaRadnju(any(), any(), any())).thenReturn(rucni);
        when(service.register(RN, id, ACTOR, rucni)).thenThrow(new com.str.backend.exception.ConflictException(
                "error.objection.alreadyRegistered", "OBJECTION_ALREADY_REGISTERED"));

        mvc.perform(post(BASE + "/{id}/register", RN, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"klasa\":\"K\",\"urbroj\":\"U-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("OBJECTION_ALREADY_REGISTERED"));
    }

    private static SuspensionObjectionDto dto(UUID id, SuspensionObjectionDto.Urudzbiranje urudzbiranje) {
        return new SuspensionObjectionDto(id, RN, "99999999990", null, null,
                com.str.backend.domain.RnStatus.SUSPENDED, "prigovor.pdf", "application/pdf", 4,
                SuspensionObjectionStatus.SUBMITTED, java.time.Instant.now(), null, null, null,
                java.util.List.of(), java.util.List.of(), true, urudzbiranje);
    }

    /**
     * RB iz druge županije županijskom službeniku je nepostojeći: svi endpointi prigovora 404, a
     * servis (popis, datoteka, prihvaćanje, odbijanje) se ne zove.
     */
    @Test
    void rnOutsideCountyScope_returns404_andServiceIsNeverCalled() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("rn not found: " + RN))
                .when(scopeGuard).requireInScope(eq(RN), any());

        mvc.perform(get(BASE, RN)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/{id}/file", RN, id)).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/{id}/accept", RN, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Dostavljena suglasnost\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/{id}/reject", RN, id)).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/{id}/register", RN, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"klasa\":\"K\",\"urbroj\":\"U-1\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service, rucniAkt);
    }
}
