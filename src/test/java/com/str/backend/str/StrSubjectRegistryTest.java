package com.str.backend.str;

import com.str.backend.address.HouseNumberRepository;
import com.str.backend.lessor.RegistrySubject;
import com.str.backend.lessor.SubjectDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Privremeni izvor podataka o subjektu dok OIB sustav nije dostupan — isto ponašanje kao raniji
 * {@code StrLessorLookupService}, samo kroz {@code SubjectRegistry} port.
 */
@ExtendWith(MockitoExtension.class)
class StrSubjectRegistryTest {

    @Mock private StrSubjectRepository subjectRepo;
    @Mock private StrSubjectVersionRepository versionRepo;
    @Mock private StrSubjectAddressRepository addressRepo;
    @Mock private HouseNumberRepository houseNumberRepo;

    private StrSubjectRegistry registry;

    private static final String OIB = "12312312316";

    @BeforeEach
    void setUp() {
        registry = new StrSubjectRegistry(subjectRepo, versionRepo, addressRepo, houseNumberRepo);
    }

    @Test
    void unknown_whenSubjectMissing() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.empty());

        assertThat(registry.findByOib(OIB)).isEmpty();
    }

    @Test
    void unknown_whenNoActiveVersion() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.of(subject(1L)));
        when(versionRepo.findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(1L))
                .thenReturn(Optional.empty());

        assertThat(registry.findByOib(OIB)).isEmpty();
    }

    @Test
    void resolvesNameAndAddress_includingCounty() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.of(subject(1L)));
        when(versionRepo.findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(1L))
                .thenReturn(Optional.of(version(10L, "Pero", "Perić", null)));
        when(addressRepo.findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(10L))
                .thenReturn(Optional.of(subjectAddress(10L, 10011L)));
        HouseNumberRepository.LessorAddressProjection address = addressProjection(
                "Ilica", "1", "Zagreb", "Grad Zagreb");
        when(houseNumberRepo.resolveFullAddress(10011L)).thenReturn(Optional.of(address));

        RegistrySubject s = registry.findByOib(OIB).orElseThrow();

        assertThat(s.firstName()).isEqualTo("Pero");
        assertThat(s.lastName()).isEqualTo("Perić");
        assertThat(s.street()).isEqualTo("Ilica");
        assertThat(s.streetNumber()).isEqualTo("1");
        assertThat(s.place()).isEqualTo("Zagreb");
        // str.subject daje županiju izravno — GO-1 radi kao i prije
        assertThat(s.county()).isEqualTo("Grad Zagreb");
        assertThat(s.source()).isEqualTo(SubjectDataSource.STR_SUBJEKT);
    }

    /**
     * Adresa nije blokator: na CDU {@code str.subject_address.address_id} ne pogađa uvijek
     * {@code eturizam_test.ar_address}. Subjekt se vraća s praznom adresom umjesto „ne postoji".
     */
    @Test
    void resolvesWithoutAddress_whenAddressNotFound() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.of(subject(1L)));
        when(versionRepo.findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(1L))
                .thenReturn(Optional.of(version(10L, "Pero", "Perić", null)));
        when(addressRepo.findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(10L))
                .thenReturn(Optional.empty());

        RegistrySubject s = registry.findByOib(OIB).orElseThrow();

        assertThat(s.firstName()).isEqualTo("Pero");
        assertThat(s.street()).isNull();
        assertThat(s.county()).isNull();
    }

    @Test
    void carriesLegalEntityName() {
        when(subjectRepo.findFirstByJipsAndActiveTrue(OIB)).thenReturn(Optional.of(subject(1L)));
        when(versionRepo.findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(1L))
                .thenReturn(Optional.of(version(10L, null, null, "Adria d.o.o.")));
        when(addressRepo.findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(10L))
                .thenReturn(Optional.empty());

        assertThat(registry.findByOib(OIB).orElseThrow().legalEntityName()).isEqualTo("Adria d.o.o.");
    }

    // --- fixtures ---

    private HouseNumberRepository.LessorAddressProjection addressProjection(
            String street, String streetNumber, String settlement, String county) {
        HouseNumberRepository.LessorAddressProjection p = mock(HouseNumberRepository.LessorAddressProjection.class);
        when(p.getStreet()).thenReturn(street);
        when(p.getStreetNumber()).thenReturn(streetNumber);
        when(p.getSettlement()).thenReturn(settlement);
        when(p.getCounty()).thenReturn(county);
        return p;
    }

    private StrSubjectEntity subject(long id) {
        StrSubjectEntity s = instantiate(StrSubjectEntity.class);
        set(s, "id", id);
        set(s, "active", true);
        set(s, "jips", OIB);
        return s;
    }

    private StrSubjectVersionEntity version(long id, String firstName, String lastName, String name) {
        StrSubjectVersionEntity v = instantiate(StrSubjectVersionEntity.class);
        set(v, "id", id);
        set(v, "active", true);
        set(v, "historical", false);
        set(v, "subjectId", 1L);
        set(v, "firstName", firstName);
        set(v, "lastName", lastName);
        set(v, "name", name);
        set(v, "pin", OIB);
        return v;
    }

    private StrSubjectAddressEntity subjectAddress(long versionId, long addressId) {
        StrSubjectAddressEntity sa = instantiate(StrSubjectAddressEntity.class);
        set(sa, "id", 99L);
        set(sa, "active", true);
        set(sa, "subjectVersionId", versionId);
        set(sa, "addressId", addressId);
        return sa;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
