package com.str.backend.objection;

import com.str.backend.auth.internal.InternalAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wiring kontrolera; logika je u {@link SuspensionObjectionAdminServiceTest}. */
class SuspensionObjectionControllerTest {

    private static final String RN = "HR120001000000000123";
    private static final String BASE = "/api/rn/{rn}/objections";
    private static final String ACTOR = "INTERNAL:12345678903";

    private final SuspensionObjectionAdminService service = mock(SuspensionObjectionAdminService.class);
    private final InternalAccessService internalAccess = mock(InternalAccessService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SuspensionObjectionController(service, internalAccess)).build();
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

        verify(service).accept(RN, id, ACTOR, "Dostavljena suglasnost");
    }

    @Test
    void reject_withoutBody_hasNoNote() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post(BASE + "/{id}/reject", RN, id))
                .andExpect(status().isNoContent());

        verify(service).reject(eq(RN), eq(id), eq(ACTOR), isNull());
    }
}
