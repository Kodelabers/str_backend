package com.str.backend.address;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Županija iz općine — o njoj ovisi GO-1 (status domaćina), a OIB sustav je ne vraća.
 */
class CountyByMunicipalityResolverTest {

    private final MunicipalityRepository municipalities = mock(MunicipalityRepository.class);
    private final CountyRepository counties = mock(CountyRepository.class);
    private final CountyByMunicipalityResolver resolver = new CountyByMunicipalityResolver(municipalities, counties);

    @Test
    void resolvesCounty_caseInsensitive_withoutPrefix() {
        when(municipalities.findByNameIgnoreCase("SPLIT")).thenReturn(List.of(municipality(17)));
        when(counties.findFirstByZuRb(17)).thenReturn(Optional.of(county("Splitsko-dalmatinska županija")));

        assertThat(resolver.countyOf("GRAD SPLIT")).contains("Splitsko-dalmatinska županija");
    }

    /** Pogrešna županija bila bi gora od prazne — tiho bi promijenila status domaćina. */
    @Test
    void ambiguousName_givesNothing() {
        when(municipalities.findByNameIgnoreCase("Sveti Juraj"))
                .thenReturn(List.of(municipality(8), municipality(9)));

        assertThat(resolver.countyOf("Sveti Juraj")).isEmpty();
    }

    @Test
    void unknownOrBlank_givesNothing() {
        when(municipalities.findByNameIgnoreCase(anyString())).thenReturn(List.of());

        assertThat(resolver.countyOf("Nepostojeće")).isEmpty();
        assertThat(resolver.countyOf("  ")).isEmpty();
        assertThat(resolver.countyOf(null)).isEmpty();
        verify(counties, never()).findFirstByZuRb(anyInt());
    }

    @Test
    void stripsGradAndOpcinaPrefix() {
        assertThat(CountyByMunicipalityResolver.stripPrefix("  Općina   Podstrana ")).isEqualTo("Podstrana");
        assertThat(CountyByMunicipalityResolver.stripPrefix("OPCINA PODSTRANA")).isEqualTo("PODSTRANA");
        assertThat(CountyByMunicipalityResolver.stripPrefix("Gradac")).isEqualTo("Gradac");
    }

    private static MunicipalityEntity municipality(int zuRb) {
        MunicipalityEntity m = instantiate(MunicipalityEntity.class);
        set(m, "zuRb", zuRb);
        return m;
    }

    private static CountyEntity county(String name) {
        CountyEntity c = instantiate(CountyEntity.class);
        set(c, "name", name);
        return c;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
