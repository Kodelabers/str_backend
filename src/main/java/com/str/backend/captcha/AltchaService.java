package com.str.backend.captcha;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

public class AltchaService {

    private static final Logger log = LoggerFactory.getLogger(AltchaService.class);
    private static final String ALGORITHM = "SHA-256";
    private static final String DEFAULT_HMAC_KEY = "change-me-in-production";

    private final AltchaProperties props;
    private final ObjectMapper mapper;
    // SecureRandom is thread-safe in all JDK implementations (synchronized internally).
    private final SecureRandom random = new SecureRandom();

    public AltchaService(AltchaProperties props, ObjectMapper mapper) {
        // Blank je zaseban slučaj od ugrađenog defaulta, i gadniji: `CAPTCHA_HMAC_KEY=` u .env-u je
        // POSTAVLJENA prazna varijabla koja pregazi Spring default, pa se do sada start uspješno
        // dizao, a HMAC pucao tek na prvom pozivu formulara — SecretKeySpec odbija prazan ključ, pa
        // je GET /api/captcha/challenge vraćao 500 i sva 4 javna formulara bila neupotrebljiva bez
        // ijednog upozorenja u startup logu. Bolje odmah oboriti start s jasnom porukom.
        if (props.enabled() && (props.hmacKey() == null || props.hmacKey().isBlank())) {
            throw new IllegalStateException(
                    "app.captcha.hmac-key is empty or unset — set CAPTCHA_HMAC_KEY (openssl rand -base64 32). "
                            + "An empty env var overrides the Spring default, so the captcha endpoints would "
                            + "fail at runtime instead of here");
        }
        if (props.enabled() && DEFAULT_HMAC_KEY.equals(props.hmacKey())) {
            throw new IllegalStateException(
                    "app.captcha.hmac-key must be overridden via CAPTCHA_HMAC_KEY env var before enabling captcha");
        }
        this.props = props;
        this.mapper = mapper;
    }

    public ChallengeDto generateChallenge() {
        byte[] saltBytes = new byte[12];
        random.nextBytes(saltBytes);
        long expires = Instant.now().plusSeconds(props.expireSeconds()).getEpochSecond();
        String salt = HexFormat.of().formatHex(saltBytes) + "?expires=" + expires;

        long number = random.nextLong(props.maxNumber());
        String challenge = HexFormat.of().formatHex(sha256bytes(salt + number));
        String signature = HexFormat.of().formatHex(hmacSha256bytes(challenge));

        return new ChallengeDto(ALGORITHM, challenge, props.maxNumber(), salt, signature);
    }

    /**
     * Decodes and verifies the base64 ALTCHA payload from the X-Altcha header.
     * No-ops when captcha is disabled (local / CI). Throws CaptchaException (→ 422)
     * on any failure so the caller gets a clean response without exposing crypto details.
     */
    public void verifyOrThrow(String payload) {
        if (!props.enabled()) {
            return;
        }
        if (payload == null || payload.isBlank()) {
            throw new CaptchaException("error.captcha.required");
        }
        try {
            byte[] json = Base64.getDecoder().decode(payload.strip());
            SolvedPayload solved = mapper.readValue(json, SolvedPayload.class);

            // Expiry check — salt contains "?expires=<epoch_seconds>"
            String saltQuery = solved.salt().contains("?")
                    ? solved.salt().substring(solved.salt().indexOf('?') + 1) : "";
            for (String part : saltQuery.split("&")) {
                if (part.startsWith("expires=")) {
                    long exp = Long.parseLong(part.substring("expires=".length()));
                    if (Instant.now().getEpochSecond() > exp) {
                        throw new CaptchaException("error.captcha.expired");
                    }
                    break;
                }
            }

            // Recompute challenge and signature; compare with constant-time equality
            // to prevent timing side-channel attacks on the HMAC.
            byte[] expectedChallenge = sha256bytes(solved.salt() + solved.number());
            byte[] actualChallenge = HexFormat.of().parseHex(solved.challenge());
            if (!MessageDigest.isEqual(expectedChallenge, actualChallenge)) {
                throw new CaptchaException("error.captcha.invalid");
            }

            byte[] expectedSignature = hmacSha256bytes(HexFormat.of().formatHex(expectedChallenge));
            byte[] actualSignature = HexFormat.of().parseHex(solved.signature());
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                throw new CaptchaException("error.captcha.invalid");
            }
        } catch (CaptchaException e) {
            throw e;
        } catch (Exception e) {
            log.debug("ALTCHA verification failed", e);
            throw new CaptchaException("error.captcha.invalid");
        }
    }

    private byte[] sha256bytes(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private byte[] hmacSha256bytes(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.hmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SolvedPayload(
            String algorithm,
            String challenge,
            long number,
            String salt,
            String signature
    ) {
    }
}
