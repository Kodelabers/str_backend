package com.str.backend.sdep;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Javni ključ SDEP-a dolazi iz env varijable SDEP_PUBLIC_KEY, kao base64 u jednom retku. */
class SdepPublicKeyConfigTest {

    @Test
    void keyFromEnvVariable_isAccepted() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
        String base64 = Base64.getEncoder().encodeToString(key.getEncoded());

        assertThat(SdepSecurityConfig.readPublicKey(base64, new DefaultResourceLoader())).isEqualTo(key);
        assertThat(SdepSecurityConfig.readPublicKey(
                "-----BEGIN PUBLIC KEY-----" + base64 + "-----END PUBLIC KEY-----", new DefaultResourceLoader()))
                .isEqualTo(key);
    }

    @Test
    void invalidKey_failsOnStartup() {
        assertThatThrownBy(() -> SdepSecurityConfig.readPublicKey("nije-kljuc", new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class);
    }
}
