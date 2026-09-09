package com.str.backend.captcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ključ se provjerava u konstruktoru, a ne pri prvom pozivu formulara.
 *
 * <p>Prazan ključ je bio tiši i gadniji slučaj od nepostavljenog: {@code CAPTCHA_HMAC_KEY=} u
 * .env-u je postavljena prazna varijabla koja pregazi Spring default, pa se aplikacija uspješno
 * dizala, a HMAC pucao tek na {@code GET /api/captcha/challenge} — 500 i sva 4 javna formulara
 * neupotrebljiva, bez ijednog upozorenja u startup logu.
 */
class AltchaServiceKeyValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AltchaProperties props(boolean enabled, String key) {
        return new AltchaProperties(enabled, key, 1000, 600);
    }

    @Test
    @DisplayName("prazan ključ obara start kad je captcha uključena")
    void rejects_blankKey_whenEnabled() {
        assertThatThrownBy(() -> new AltchaService(props(true, ""), MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAPTCHA_HMAC_KEY");
    }

    @Test
    @DisplayName("ključ od samih razmaka jednako obara start — isBlank, ne isEmpty")
    void rejects_whitespaceKey_whenEnabled() {
        assertThatThrownBy(() -> new AltchaService(props(true, "   "), MAPPER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("nepostavljen ključ (null) obara start")
    void rejects_nullKey_whenEnabled() {
        assertThatThrownBy(() -> new AltchaService(props(true, null), MAPPER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ugrađeni default obara start")
    void rejects_builtInDefault_whenEnabled() {
        assertThatThrownBy(() -> new AltchaService(props(true, "change-me-in-production"), MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be overridden");
    }

    @Test
    @DisplayName("ugašena captcha ne provjerava ključ — local profil i CI se dižu s defaultom")
    void allows_anyKey_whenDisabled() {
        assertThatCode(() -> new AltchaService(props(false, ""), MAPPER)).doesNotThrowAnyException();
        assertThatCode(() -> new AltchaService(props(false, "change-me-in-production"), MAPPER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("s valjanim ključem izazov se generira — dokaz da bi prazan ključ pukao tek ovdje")
    void generatesChallenge_withRealKey() {
        AltchaService service = new AltchaService(props(true, "c21va2UtdGVzdC1rZXk="), MAPPER);
        ChallengeDto challenge = service.generateChallenge();
        assertThat(challenge.challenge()).isNotBlank();
        assertThat(challenge.signature()).isNotBlank();
        assertThat(challenge.salt()).contains("?expires=");
    }
}
