package com.str.backend.draft;

import com.str.backend.auth.nias.ActingSubject;
import com.str.backend.auth.nias.ActingSubjectGuard;
import com.str.backend.auth.nias.ActingSubjectService;
import com.str.backend.auth.nias.EffectiveOibResolver;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.draft.dto.DraftListItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nacrt pripada subjektu u čije ime se djeluje: nacrt pripremljen za sebe ne smije se spremiti,
 * izmijeniti ni obrisati kao tvrtkin nakon odabira tvrtke u drugom prozoru ({@code X-Acting-Subject}).
 */
@ActiveProfiles("test")
@Import({ActingSubjectGuard.class, EffectiveOibResolver.class})
@WebMvcTest(SubmissionDraftController.class)
@AutoConfigureMockMvc(addFilters = false)
class SubmissionDraftControllerActingSubjectTest {

    private static final String PERSON = "99999999990";
    private static final String COMPANY = "33333333360";
    private static final String BODY = "{\"title\":\"Apartman\",\"payload\":\"{}\"}";

    @Autowired MockMvc mvc;

    @MockBean NiasOibResolver niasOibResolver;
    @MockBean ActingSubjectService actingSubjectService;
    @MockBean SubmissionDraftService service;
    @MockBean DraftOwnerResolver ownerResolver;

    @BeforeEach
    void niasPerson() {
        when(niasOibResolver.resolve(any())).thenReturn(Optional.of(PERSON));
        when(ownerResolver.resolve(any(), any())).thenReturn(new DraftOwner(DraftOwnerType.NIAS_OIB, PERSON));
        when(service.create(any(), any())).thenReturn(new SubmissionDraftService.SaveResult(item(), true));
        when(service.update(any(), any(), any())).thenReturn(item());
    }

    /** Bez zaglavlja — kao i dosad (frontend koji ga još ne šalje). */
    @Test
    void create_withoutHeader_proceeds() throws Exception {
        mvc.perform(post("/api/drafts").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        verify(service).create(any(), any());
    }

    @Test
    void create_matchingHeader_proceeds() throws Exception {
        mvc.perform(post("/api/drafts").header("X-Acting-Subject", "SELF")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void create_headerSelf_whileActingForCompany_is409() throws Exception {
        actingForCompany();

        mvc.perform(post("/api/drafts").header("X-Acting-Subject", "SELF")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("ACTING_SUBJECT_CHANGED"))
                .andExpect(jsonPath("$.details.current").value(COMPANY));
        verify(service, never()).create(any(), any());
    }

    @Test
    void update_headerCompany_whileOwnName_is409() throws Exception {
        mvc.perform(put("/api/drafts/" + UUID.randomUUID()).header("X-Acting-Subject", COMPANY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        verify(service, never()).update(any(), any(), any());
    }

    @Test
    void delete_headerSelf_whileActingForCompany_is409() throws Exception {
        actingForCompany();

        mvc.perform(delete("/api/drafts/" + UUID.randomUUID()).header("X-Acting-Subject", "SELF"))
                .andExpect(status().isConflict());
        verify(service, never()).delete(any(), any());
    }

    @Test
    void delete_matchingCompanyHeader_proceeds() throws Exception {
        actingForCompany();

        mvc.perform(delete("/api/drafts/" + UUID.randomUUID()).header("X-Acting-Subject", COMPANY))
                .andExpect(status().isNoContent());
        verify(service).delete(any(), any());
    }

    /** Upsert: vlasnik je za isti objekt već imao nacrt — 200 s njegovim draftId, ne 201. */
    @Test
    void create_upsertOfExistingFacilityDraft_is200() throws Exception {
        DraftListItemResponse existing = item();
        when(service.create(any(), any())).thenReturn(new SubmissionDraftService.SaveResult(existing, false));

        mvc.perform(post("/api/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Apartman\",\"payload\":\"{}\",\"facilityId\":\"4711\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftId").value(existing.draftId().toString()));
    }

    @Test
    void list_byFacility_passesFilterToService() throws Exception {
        when(service.list(any(), eq("4711"))).thenReturn(List.of(item()));

        mvc.perform(get("/api/drafts").param("facilityId", "4711"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        verify(service).list(new DraftOwner(DraftOwnerType.NIAS_OIB, PERSON), "4711");
    }

    private void actingForCompany() {
        when(actingSubjectService.current(any(), eq(PERSON))).thenReturn(Optional.of(new ActingSubject(
                COMPANY, "TESTNA TVRTKA d.o.o.", List.of("Direktor"), PERSON, "Test", "Korisnik", Instant.now())));
    }

    private static DraftListItemResponse item() {
        return new DraftListItemResponse(UUID.randomUUID(), "Apartman", DraftOwnerType.NIAS_OIB, null, Instant.now(), Instant.now());
    }
}
