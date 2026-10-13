package com.str.backend.address;

import com.str.backend.address.AddressIdResolver.AddressIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nazivi → id-evi registra niz kaskadu; samo jednoznačno poklapanje, inače {@code null} odozdo. */
class AddressIdResolverTest {

    private CountyRepository counties;
    private MunicipalityRepository municipalities;
    private SettlementRepository settlements;
    private StreetRepository streets;
    private HouseNumberRepository houseNumbers;
    private AddressIdResolver resolver;

    @BeforeEach
    void setUp() {
        counties = mock(CountyRepository.class);
        municipalities = mock(MunicipalityRepository.class);
        settlements = mock(SettlementRepository.class);
        streets = mock(StreetRepository.class);
        houseNumbers = mock(HouseNumberRepository.class);
        resolver = new AddressIdResolver(counties, municipalities, settlements, streets, houseNumbers);

        when(counties.findAllByOrderByZuRb()).thenReturn(List.of(
                entity(CountyEntity.class, 1L, "Istarska županija"),
                entity(CountyEntity.class, 7L, "Splitsko-dalmatinska županija")));
        when(municipalities.findByCountyIdOrderByName(7L, "Split"))
                .thenReturn(List.of(entity(MunicipalityEntity.class, 70L, "Split")));
        // Naselje s dva poštanska broja dolazi u dva retka s istim id-em.
        when(settlements.findByMunicipalityIdOrderByName(70L, "Split"))
                .thenReturn(List.of(settlement(700L, "Split"), settlement(700L, "Split")));
        when(streets.findBySettlementIdOrderByName(700L, "Riva"))
                .thenReturn(List.of(entity(StreetEntity.class, 7000L, "Riva"),
                        entity(StreetEntity.class, 7001L, "Riva Sjever")));
        when(houseNumbers.findByStreetIdOrderByName(7000L, "1"))
                .thenReturn(List.of(entity(HouseNumberEntity.class, 70000L, "1"),
                        entity(HouseNumberEntity.class, 70001L, "1A")));
    }

    @Test
    void resolvesWholeHierarchy_ignoringCaseAndWhitespace() {
        AddressIds ids = resolver.resolve("splitsko-dalmatinska  županija", "Split", "Split", "Riva", "1");

        assertThat(ids).isEqualTo(new AddressIds(7L, 70L, 700L, 7000L, 70000L));
    }

    @Test
    void ambiguousLevel_stopsCascade() {
        when(streets.findBySettlementIdOrderByName(700L, "Riva"))
                .thenReturn(List.of(entity(StreetEntity.class, 7000L, "Riva"), entity(StreetEntity.class, 7002L, "riva")));

        AddressIds ids = resolver.resolve("Splitsko-dalmatinska županija", "Split", "Split", "Riva", "1");

        assertThat(ids).isEqualTo(new AddressIds(7L, 70L, 700L, null, null));
        verify(houseNumbers, never()).findByStreetIdOrderByName(anyLong(), any());
    }

    @Test
    void unknownCounty_resolvesNothing() {
        assertThat(resolver.resolve("Nepostojeća županija", "Split", "Split", "Riva", "1"))
                .isEqualTo(AddressIds.NONE);
        verify(municipalities, never()).findByCountyIdOrderByName(anyLong(), any());
    }

    @Test
    void missingSettlement_keepsUpperLevels() {
        assertThat(resolver.resolve("Splitsko-dalmatinska županija", "Split", null, "Riva", "1"))
                .isEqualTo(new AddressIds(7L, 70L, null, null, null));
    }

    @Test
    void nullCounty_resolvesNothing() {
        assertThat(resolver.resolve(null, null, null, null, null)).isEqualTo(AddressIds.NONE);
    }

    private static SettlementRepository.SettlementProjection settlement(Long id, String name) {
        return new SettlementRepository.SettlementProjection() {
            @Override public Long getId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getPostalCode() { return "21000"; }
        };
    }

    private static <T> T entity(Class<T> type, Long id, String name) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            T e = ctor.newInstance();
            Field idField = type.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
            Field nameField = type.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(e, name);
            return e;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
