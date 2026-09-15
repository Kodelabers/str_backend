package com.str.backend.address;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ar_address.kc_broj} na pravoj bazi nije goli broj čestice nego
 * {@code <MB katastarske općine>|<broj čestice>} — izmjereno na predprodukciji 11.09.2026.
 * (200 000/200 000 redaka u tom obliku). Lokalni mock je dosad držao goli broj, pa split mora
 * podnijeti oba oblika.
 */
class HouseNumberEntityKcTest {

    @Test
    void kcCestica_stripsCadastralMunicipalityPrefix() {
        assertThat(entity("300071|666/3").getKcCestica()).isEqualTo("666/3");
        assertThat(entity("310743|1").getKcCestica()).isEqualTo("1");
    }

    /** Stari lokalni mock i svaki registar bez prefiksa moraju proći netaknuti. */
    @Test
    void kcCestica_valueWithoutPrefix_passesThrough() {
        assertThat(entity("1201/1").getKcCestica()).isEqualTo("1201/1");
    }

    @Test
    void kcCestica_nullOrEmptyTail_isNull() {
        assertThat(entity(null).getKcCestica()).isNull();
        assertThat(entity("300071|").getKcCestica()).isNull();
    }

    /** Isto pravilo koristi CadastreResolver nad retkom iz upita, bez entiteta. */
    @Test
    void cestica_static_trimsAndMatchesInstanceGetter() {
        assertThat(HouseNumberEntity.cestica("300071| 666/3 ")).isEqualTo("666/3");
        assertThat(HouseNumberEntity.cestica("300071|666/3")).isEqualTo(entity("300071|666/3").getKcCestica());
        assertThat(HouseNumberEntity.cestica("   ")).isNull();
    }

    private HouseNumberEntity entity(String kcBroj) {
        try {
            var ctor = HouseNumberEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            HouseNumberEntity e = ctor.newInstance();
            Field f = HouseNumberEntity.class.getDeclaredField("kcBroj");
            f.setAccessible(true);
            f.set(e, kcBroj);
            return e;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
