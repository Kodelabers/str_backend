package com.str.backend.registries.eovlastenja;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app.eovlastenja.mock[*]} se veže kroz kanonski konstruktor recorda. Uz drugi javni konstruktor
 * Spring Boot ne zna koji koristiti i mock parovi ostanu nevezani — lokalno tada nijedno zastupanje ne
 * postoji.
 */
class EOvlastenjaPropertiesBindingTest {

    @Test
    void mockPairs_bindIncludingRegistry() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "app.eovlastenja.enabled", "false",
                "app.eovlastenja.mock[0].person-oib", "99999999990",
                "app.eovlastenja.mock[0].legal-oib", "33333333360",
                "app.eovlastenja.mock[0].legal-name", "TESTNA TVRTKA d.o.o.",
                "app.eovlastenja.mock[1].person-oib", "99999999990",
                "app.eovlastenja.mock[1].legal-oib", "55555555551",
                "app.eovlastenja.mock[1].legal-name", "OPG TESTNI",
                "app.eovlastenja.mock[1].izvor-reg", "3"));

        EOvlastenjaProperties props = new Binder(source).bind("app.eovlastenja", EOvlastenjaProperties.class).get();

        assertThat(props.mockOrEmpty()).hasSize(2);
        assertThat(props.mockOrEmpty().get(0).legalForm()).isEqualTo(LegalForm.TVRTKA);
        assertThat(props.mockOrEmpty().get(1).legalForm()).isEqualTo(LegalForm.OPG);
    }
}
