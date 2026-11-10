package com.str.backend.lookup;

import com.str.backend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pravilo T13: fizička osoba bira vrste domaćinstva, OPG vrste ugostiteljskih usluga u OPG-u, a
 * ostali poslovni subjekti skupine objekata.
 */
class ObjectGroupPolicyTest {

    private static final String DOM = "FT_UGOST_USL_U_DOM";
    private static final String OPG = "FT_UGOST_USL_U_OPG";

    private final ObjectGroupRepository groupRepository = mock(ObjectGroupRepository.class);
    private final AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
    private final ObjectGroupPolicy policy = new ObjectGroupPolicy(groupRepository, typeRepository);

    private final AccommodationTypeEntity soba = type(1L, "FS_SOBA", DOM, true);
    private final AccommodationTypeEntity sobaOpg = type(5L, "FS_SOBA_OPG", OPG, true);
    private final AccommodationTypeEntity ostaliKat = type(20L, "FT_OST_KAT", "FT_OST_KAT", true);
    private final AccommodationTypeEntity hoteli = type(21L, "FT_HOTEL", "FT_HOTEL", true);

    @BeforeEach
    void setUp() {
        when(groupRepository.findGroupCodes(SubjectCategory.FIZICKA_OSOBA)).thenReturn(List.of(DOM));
        when(groupRepository.findGroupCodes(SubjectCategory.OPG)).thenReturn(List.of(OPG));
        when(groupRepository.findGroupCodes(SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))
                .thenReturn(List.of("FT_OST_KAT", "FT_HOTEL"));
        for (AccommodationTypeEntity t : List.of(soba, sobaOpg, ostaliKat, hoteli)) {
            when(typeRepository.findByCodeIgnoreCase(t.getCode())).thenReturn(Optional.of(t));
            when(typeRepository.findById(t.getTypeId())).thenReturn(Optional.of(t));
        }
    }

    @Test
    void resolve_naturalPerson_byType() {
        assertThat(policy.resolveFacilityType("FS_SOBA", "FT_OST_KAT", SubjectCategory.FIZICKA_OSOBA)).contains(soba);
    }

    /** Ista vrsta FS_SOBA tvrtki je dio skupine FT_OST_KAT, a ne domaćinstvo. */
    @Test
    void resolve_company_fallsBackToGroup() {
        assertThat(policy.resolveFacilityType("FS_SOBA", "FT_OST_KAT", SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))
                .contains(ostaliKat);
        assertThat(policy.resolveFacilityType("FS_HOTEL_FAMILY", "FT_HOTEL", SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))
                .contains(hoteli);
    }

    @Test
    void resolve_opg_byOwnTypes_only() {
        assertThat(policy.resolveFacilityType("FS_SOBA_OPG", OPG, SubjectCategory.OPG)).contains(sobaOpg);
        assertThat(policy.resolveFacilityType("FS_SOBA", "FT_OST_KAT", SubjectCategory.OPG)).isEmpty();
    }

    @Test
    void resolve_naturalPerson_hotel_isEmpty() {
        assertThat(policy.resolveFacilityType("FS_HOTEL_FAMILY", "FT_HOTEL", SubjectCategory.FIZICKA_OSOBA)).isEmpty();
    }

    @Test
    void isRegistrable_unknownTypeAndGroup_isNotBlocked() {
        assertThat(policy.isRegistrable(null, null, SubjectCategory.OSTALI_POSLOVNI_SUBJEKT)).isTrue();
        assertThat(policy.isRegistrable("FS_HOTEL_FAMILY", "FT_HOTEL", SubjectCategory.FIZICKA_OSOBA)).isFalse();
        assertThat(policy.isRegistrable("FS_HOTEL_FAMILY", "FT_HOTEL", SubjectCategory.OSTALI_POSLOVNI_SUBJEKT)).isTrue();
    }

    @Test
    void requireTypeAllowed_followsCategory() {
        assertThatCode(() -> policy.requireTypeAllowed(1L, SubjectCategory.FIZICKA_OSOBA)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireTypeAllowed(21L, SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requireTypeAllowed(5L, SubjectCategory.OPG)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireTypeAllowed(1L, SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.accommodation.type.notAllowedForSubject");
        assertThatThrownBy(() -> policy.requireTypeAllowed(21L, SubjectCategory.FIZICKA_OSOBA))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireTypeAllowed(1L, SubjectCategory.OPG))
                .isInstanceOf(BusinessException.class);
    }

    /** Bez vrste nema što provjeriti; nepoznatu vrstu odbija razrješavanje zahtjeva. */
    @Test
    void requireTypeAllowed_missingType_isNotChecked_unknownIsRejected() {
        when(typeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatCode(() -> policy.requireTypeAllowed(null, SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireTypeAllowed(99L, SubjectCategory.FIZICKA_OSOBA))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rbGroupCode_comesFromGroup_andDefaultsToZero() {
        when(groupRepository.findById("FT_HOTEL")).thenReturn(Optional.of(
                new ObjectGroupEntity("FT_HOTEL", "Hoteli", 3, Set.of(SubjectCategory.OSTALI_POSLOVNI_SUBJEKT))));

        assertThat(policy.rbGroupCode(21L)).isEqualTo(3);
        assertThat(policy.rbGroupCode(null)).isZero();
        assertThat(policy.rbGroupCode(1L)).isZero();
    }

    /** typeId i code puni Liquibase, pa se u testu postavljaju refleksijom. */
    private static AccommodationTypeEntity type(long id, String code, String group, boolean rnAllowed) {
        AccommodationTypeEntity entity = new AccommodationTypeEntity(code, rnAllowed, group);
        set(entity, "typeId", id);
        set(entity, "code", code);
        return entity;
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
