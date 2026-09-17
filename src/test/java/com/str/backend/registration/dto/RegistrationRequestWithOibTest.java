package com.str.backend.registration.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RegistrationRequest#withOib} ručno prepisuje tridesetak komponenti. Zaboravljeno ili
 * zamijenjeno polje značilo bi tiho izgubljen podatak na svakom NIAS zahtjevu — kontroler preko
 * {@code withOib} pregazi OIB iz tijela onim iz SAML asercije. Test prolazi kroz komponente
 * refleksijom, pa pukne i kad netko doda novo polje, a ne dopiše ga u kopiju.
 */
class RegistrationRequestWithOibTest {

    private static final String NOVI_OIB = "99999999999";

    @Test
    void withOib_copiesEveryComponentExceptOib() throws Exception {
        RecordComponent[] components = RegistrationRequest.class.getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = sample(components[i].getType(), i);
        }
        RegistrationRequest original = RegistrationRequest.class.getDeclaredConstructor(types).newInstance(values);

        RegistrationRequest copy = RegistrationRequest.withOib(original, NOVI_OIB);

        for (RecordComponent component : components) {
            Object expected = component.getName().equals("oib")
                    ? NOVI_OIB
                    : component.getAccessor().invoke(original);
            assertThat(component.getAccessor().invoke(copy)).as(component.getName()).isEqualTo(expected);
        }
    }

    /**
     * Različita vrijednost po poziciji, da se uhvati i zamjena dvaju polja istog tipa, a ne samo
     * izostavljeno polje. Novi tip komponente traži dopunu ovdje — test tada pukne s porukom.
     */
    private static Object sample(Class<?> type, int index) {
        if (type == String.class) {
            return "v" + index;
        }
        if (type == Long.class) {
            return 1000L + index;
        }
        if (type == int.class || type == Integer.class) {
            return 1000 + index;
        }
        if (type == Boolean.class) {
            return index % 2 == 0;
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2026, 1, 1).plusDays(index);
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants[index % constants.length];
        }
        throw new IllegalStateException("Nepodržan tip komponente " + type + " — dopuni sample()");
    }
}
