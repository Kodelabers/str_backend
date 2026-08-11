package com.str.backend.admin;

import com.str.backend.categorization.CategorizationFileDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiring kontrolera (routanje, path-varijable, headeri, statusi, vezanje tijela). Logika i
 * prijelazi statusa pokriveni su u {@link AdminCategorizationDecisionServiceTest}. {@code list}
 * se ovdje ne testira jer sirovi {@code Page} ne serijalizira standalone MockMvc bez Spring Data
 * {@code PageModule} — to pokriva servisni test.
 */
class AdminCategorizationDecisionControllerTest {

    private static final String BASE = "/api/admin/categorization-decisions";

    private final AdminCategorizationDecisionService service = mock(AdminCategorizationDecisionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminCategorizationDecisionController(service))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void file_setsInlineHeaderAndContentType() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.file(id)).thenReturn(new CategorizationFileDto(
                "rjesenje.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.US_ASCII)));

        mvc.perform(get(BASE + "/{id}/file", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("rjesenje.pdf")))
                .andExpect(content -> org.assertj.core.api.Assertions.assertThat(
                        content.getResponse().getContentType()).isEqualTo("application/pdf"));
    }

    @Test
    void verify_noBody_delegatesWithNullActor() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post(BASE + "/{id}/verify", id))
                .andExpect(status().isNoContent());

        verify(service).verify(eq(id), isNull());
    }

    @Test
    void reject_withBody_passesActorAndReason() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post(BASE + "/{id}/reject", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"sluzbenik-1\",\"reason\":\"sken nečitak\"}"))
                .andExpect(status().isNoContent());

        verify(service).reject(id, "sluzbenik-1", "sken nečitak");
    }
}
