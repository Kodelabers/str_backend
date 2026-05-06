package com.str.backend.str;

import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrLessorLookupServiceTest {

    @Mock private StrSubjectRepository subjectRepo;
    @Mock private StrSubjectVersionRepository versionRepo;
    @Mock private StrSubjectAddressRepository addressRepo;
    @Mock private StrAddressRepository strAddressRepo;

    private StrLessorLookupService service;

    private static final String OIB = "12312312316";

    @BeforeEach
    void setUp() {
        service = new StrLessorLookupService(subjectRepo, versionRepo, addressRepo, strAddressRepo);
    }

    @Test
    void throws_when_subject_not_found() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveLessor(OIB))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("str.subject");
    }

    @Test
    void throws_when_version_not_found() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.of(subject(1L)));
        when(versionRepo.findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveLessor(OIB))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("subject_version");
    }

    @Test
    void throws_when_address_not_found() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.of(subject(1L)));
        when(versionRepo.findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(1L))
                .thenReturn(Optional.of(version(10L, "Pero", "Perić", null, OIB)));
        when(addressRepo.findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(10L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveLessor(OIB))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Adresa");
    }

    @Test
    void resolves_lessor_from_str_schema() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.of(subject(1L)));
        when(versionRepo.findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(1L))
                .thenReturn(Optional.of(version(10L, "Pero", "Perić", null, OIB)));
        when(addressRepo.findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(10L))
                .thenReturn(Optional.of(subjectAddress(10L, 100L)));
        when(strAddressRepo.findById(100L)).thenReturn(Optional.of(address("Ilica", "1", "Zagreb", "GRAD_ZAGREB")));

        LessorEntity lessor = service.resolveLessor(OIB);

        assertThat(lessor.getFirstName()).isEqualTo("Pero");
        assertThat(lessor.getLastName()).isEqualTo("Perić");
        assertThat(lessor.getLessorOib()).isEqualTo(OIB);
        assertThat(lessor.getStreet()).isEqualTo("Ilica");
        assertThat(lessor.getEmail()).isNull();
    }

    @Test
    void sets_legal_entity_name_for_legal_persons() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.of(subject(1L)));
        when(versionRepo.findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(1L))
                .thenReturn(Optional.of(version(10L, null, null, "Adria d.o.o.", OIB)));
        when(addressRepo.findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(10L))
                .thenReturn(Optional.of(subjectAddress(10L, 100L)));
        when(strAddressRepo.findById(100L)).thenReturn(Optional.of(address("Vukovarska", "15", "Split", "SPLITSKO_DALMATINSKA")));

        LessorEntity lessor = service.resolveLessor(OIB);

        assertThat(lessor.getLegalEntityName()).isEqualTo("Adria d.o.o.");
    }

    // --- fixtures ---

    private StrSubjectEntity subject(long id) {
        try {
            var ctor = StrSubjectEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            var s = ctor.newInstance();
            set(s, "id", id);
            set(s, "active", true);
            set(s, "jips", OIB);
            return s;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private StrSubjectVersionEntity version(long id, String firstName, String lastName, String name, String pin) {
        try {
            var ctor = StrSubjectVersionEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            var v = ctor.newInstance();
            set(v, "id", id);
            set(v, "active", true);
            set(v, "historical", false);
            set(v, "subjectId", 1L);
            set(v, "firstName", firstName);
            set(v, "lastName", lastName);
            set(v, "name", name);
            set(v, "pin", pin);
            return v;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private StrSubjectAddressEntity subjectAddress(long versionId, long addressId) {
        try {
            var ctor = StrSubjectAddressEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            var sa = ctor.newInstance();
            set(sa, "id", 99L);
            set(sa, "active", true);
            set(sa, "subjectVersionId", versionId);
            set(sa, "addressId", addressId);
            return sa;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private StrAddressEntity address(String street, String houseNumber, String settlement, String county) {
        try {
            var ctor = StrAddressEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            var a = ctor.newInstance();
            set(a, "id", 100L);
            set(a, "active", true);
            set(a, "street", street);
            set(a, "houseNumber", houseNumber);
            set(a, "settlement", settlement);
            set(a, "county", county);
            return a;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
