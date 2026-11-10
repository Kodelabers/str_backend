package com.str.backend.lookup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pravilo iz {@code str_rn.subject_category_object_group} nad stvarnom pohranom (T13): koje skupine
 * smije koja vrsta subjekta i koje stavke iz njih idu u izbornik i filtar popisa.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ObjectGroupRepositoryTest {

    @Autowired private ObjectGroupRepository groupRepository;
    @Autowired private AccommodationTypeRepository typeRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // Kao changeset 156: fizička osoba domaćinstvo, OPG svoja skupina, tvrtka skupine.
        groupRepository.saveAll(List.of(
                new ObjectGroupEntity("FT_UGOST_USL_U_DOM", "Domaćinstvo", 0, Set.of(SubjectCategory.FIZICKA_OSOBA)),
                new ObjectGroupEntity("FT_UGOST_USL_U_OPG", "OPG", 0, Set.of(SubjectCategory.OPG)),
                new ObjectGroupEntity("FT_HOTEL", "Hoteli", 0, Set.of(SubjectCategory.OSTALI_POSLOVNI_SUBJEKT)),
                new ObjectGroupEntity("FT_OST_KAT", "Ostali kat.", 0, Set.of(SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))));
        type("Soba", true, "FT_UGOST_USL_U_DOM", "FS_SOBA");
        type("Apartman", true, "FT_UGOST_USL_U_DOM", "FS_APARTMAN");
        type("Soba", true, "FT_UGOST_USL_U_OPG", "FS_SOBA_OPG");
        type("Hoteli", true, "FT_HOTEL", "FT_HOTEL");
        type("Ostali kat.", false, "FT_OST_KAT", "FT_OST_KAT");
    }

    @Test
    void groups_perCategory() {
        assertThat(groupRepository.findGroupCodes(SubjectCategory.FIZICKA_OSOBA)).containsExactly("FT_UGOST_USL_U_DOM");
        assertThat(groupRepository.findGroupCodes(SubjectCategory.OPG)).containsExactly("FT_UGOST_USL_U_OPG");
        assertThat(groupRepository.findGroupCodes(SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))
                .containsExactlyInAnyOrder("FT_HOTEL", "FT_OST_KAT");
    }

    @Test
    void typesAndCodes_filteredByGroup() {
        assertThat(typeRepository.findAllByRegistrationNumberAllowedTrueAndGroupIn(List.of("FT_HOTEL", "FT_OST_KAT")))
                .extracting(AccommodationTypeEntity::getName)
                .containsExactly("Hoteli");
        assertThat(typeRepository.findCodesByGroupIn(List.of("FT_UGOST_USL_U_DOM")))
                .containsExactlyInAnyOrder("FS_SOBA", "FS_APARTMAN");
    }

    private void type(String name, boolean rnAllowed, String group, String code) {
        AccommodationTypeEntity saved = typeRepository.saveAndFlush(new AccommodationTypeEntity(name, rnAllowed, group));
        // Šifru puni Liquibase (060), entitet je ne postavlja.
        jdbc.update("UPDATE str_rn.accommodation_type SET code = ? WHERE type_id = ?", code, saved.getTypeId());
    }
}
