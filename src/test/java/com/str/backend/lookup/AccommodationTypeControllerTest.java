package com.str.backend.lookup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(AccommodationTypeController.class)
@AutoConfigureMockMvc(addFilters = false)
class AccommodationTypeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private AccommodationTypeRepository repository;

    /**
     * Fronta se veže na {@code code}, a ne na {@code id} ili {@code name} — ID-evi se
     * razlikuju među okolinama jer 020-reseed briše i ponovno umeće retke. Ako šifra
     * ispadne iz odgovora, fronta se tiho vraća na hardkodiranu listu.
     */
    @Test
    void accommodationTypes_exposeStableCode() throws Exception {
        when(repository.findAllByRegistrationNumberAllowedTrue()).thenReturn(List.of(
                type(1L, "Soba", "ostalo", "FS_SOBA"),
                type(4L, "Kuća za odmor", "ostalo", "FS_KUCA_ZA_ODMOR")));

        mvc.perform(get("/api/lookups/accommodation-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("1"))
                .andExpect(jsonPath("$[0].name").value("Soba"))
                .andExpect(jsonPath("$[0].group").value("ostalo"))
                .andExpect(jsonPath("$[0].code").value("FS_SOBA"))
                .andExpect(jsonPath("$[1].name").value("Kuća za odmor"))
                .andExpect(jsonPath("$[1].code").value("FS_KUCA_ZA_ODMOR"));
    }

    /**
     * Šifra je nullable (vrsta dodana izvan migracije 060), pa polje mora ostati u
     * odgovoru kao null umjesto da sruši serijalizaciju.
     */
    @Test
    void accommodationTypes_tolerateMissingCode() throws Exception {
        when(repository.findAllByRegistrationNumberAllowedTrue())
                .thenReturn(List.of(type(9L, "Nova vrsta", "ostalo", null)));

        mvc.perform(get("/api/lookups/accommodation-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Nova vrsta"))
                .andExpect(jsonPath("$[0].code").value(nullValue()));
    }

    // --- fixtures ---

    /** typeId i code puni Liquibase, pa ih u testu postavljamo refleksijom. */
    private static AccommodationTypeEntity type(long id, String name, String group, String code) {
        AccommodationTypeEntity entity = new AccommodationTypeEntity(name, true, group);
        set(entity, "typeId", id);
        set(entity, "code", code);
        return entity;
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
