package com.str.backend.sdep;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Javni ključ SDEP-a za CDU test je u repozitoriju i profil cdu ga koristi bez varijable okoline. */
class SdepPublicKeyConfigTest {

    @Test
    void cduProfilePointsAtBundledKey() throws IOException {
        Properties cdu = new Properties();
        try (var in = getClass().getResourceAsStream("/application-cdu.properties")) {
            cdu.load(in);
        }
        assertThat(cdu.getProperty("str.sdep.public-key"))
                .isEqualTo("${SDEP_PUBLIC_KEY:classpath:sdep/sdep-public-cdu.pub}");
    }

    @Test
    void bundledKeyIsAValidRsaPublicKey() {
        assertThatCode(() -> new SdepSecurityConfig()
                .sdepJwtDecoder("classpath:sdep/sdep-public-cdu.pub", new DefaultResourceLoader()))
                .doesNotThrowAnyException();
    }
}
