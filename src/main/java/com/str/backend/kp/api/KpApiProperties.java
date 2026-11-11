package com.str.backend.kp.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Slanje u korisnički pretinac (KP) preko eTurizmova KP API-ja ({@code str-internal-api/kp-api}),
 * {@code str.kp-api.*}. Koristi se za akte županijskih službenika, koji ne idu kroz eGOP (T16).
 *
 * <p>Prijava je <b>HTTP Basic</b> tehničkim korisnikom STR-a (eTurizam, 11. 11. 2026.), neovisno o
 * službeniku: poruka odlazi odmah nakon radnje, a ono što ne prođe (ili nastane u noćnom poslu) šalje
 * {@link KpApiRetryJob}. Lozinka se zadaje samo kroz env ({@code STR_KP_API_PASSWORD}) i nikad se ne logira.
 *
 * <p>Zadane vrijednosti žive samo ovdje; {@code application.properties} ključeve veže na env s praznim
 * defaultom, a prazno znači „zadano". Šifra poruke ({@code tip-poruke}) i adresa razlikuju se po okolini
 * (test 1741, produkcija 2081), pa ih profil daje kroz env ({@code application-cdu.properties},
 * {@code application-prod.properties}).
 *
 * @param enabled         šalje li STR uopće u KP API; ugašeno (zadano) znači samo e-pošta
 * @param baseUrl         npr. {@code https://et2-test-internal-eturizam.gov.hr/str-internal-api/kp-api}
 * @param username        korisnik za HTTP Basic
 * @param password        lozinka za HTTP Basic (samo env)
 * @param tipPoruke       ID tipa poruke iz Registra e-poruka (APIS-IT), zadani za sve akte
 * @param tipPorukePoAktu iznimke po vrsti akta ({@code StrDocumentType} ime), npr. {@code SUSPENZIJA: 1843}
 * @param maxVelicina     najviše bajtova po poruci (tekst + privici u base64); KP dopušta 3 MB
 * @param batchSize       najviše poruka u jednom prolazu {@link KpApiRetryJob}-a
 * @param maxAttempts     neuspjelih pokušaja (502, timeout, veza) prije odustajanja
 * @param backoffBase     odgoda nakon prvog neuspjeha; svaki sljedeći udvostručuje, do {@code backoffMax}
 * @param backoffMax      najdulja odgoda
 * @param connectTimeout  spajanje na KP API
 * @param readTimeout     odgovor KP API-ja (sinkron: čeka odgovor KP-a)
 * @param poolSize        niti za slanje izvan niti zahtjeva
 * @param queueCapacity   red zadataka; što ne stane, šalje retry job
 */
@ConfigurationProperties("str.kp-api")
public record KpApiProperties(
        Boolean enabled,
        String baseUrl,
        String username,
        String password,
        Integer tipPoruke,
        Map<String, Integer> tipPorukePoAktu,
        Long maxVelicina,
        Integer batchSize,
        Integer maxAttempts,
        Duration backoffBase,
        Duration backoffMax,
        Duration connectTimeout,
        Duration readTimeout,
        Integer poolSize,
        Integer queueCapacity
) {

    /** 3 MB KP-a, uz rezervu za JSON omotnicu poruke. */
    public static final long DEFAULT_MAX_VELICINA = 3_000_000L;

    public KpApiProperties {
        enabled = Boolean.TRUE.equals(enabled);
        baseUrl = blankToNull(baseUrl);
        username = blankToNull(username);
        password = password == null || password.isEmpty() ? null : password;
        tipPorukePoAktu = tipPorukePoAktu == null ? Map.of() : Map.copyOf(tipPorukePoAktu);
        maxVelicina = maxVelicina != null && maxVelicina > 0 ? maxVelicina : DEFAULT_MAX_VELICINA;
        batchSize = batchSize != null && batchSize > 0 ? batchSize : 20;
        maxAttempts = maxAttempts != null && maxAttempts > 0 ? maxAttempts : 10;
        backoffBase = positiveOrDefault(backoffBase, Duration.ofMinutes(5));
        backoffMax = positiveOrDefault(backoffMax, Duration.ofHours(2));
        connectTimeout = positiveOrDefault(connectTimeout, Duration.ofSeconds(5));
        readTimeout = positiveOrDefault(readTimeout, Duration.ofSeconds(30));
        poolSize = poolSize != null && poolSize > 0 ? poolSize : 2;
        queueCapacity = queueCapacity != null && queueCapacity > 0 ? queueCapacity : 50;
    }

    /** Slanje je uključeno i potpuno konfigurirano (adresa, prijava i šifra poruke). */
    public boolean aktivno() {
        return enabled && baseUrl != null && username != null && password != null
                && tipPoruke != null && tipPoruke > 0;
    }

    /** Što nedostaje za {@link #aktivno} — za log pri startu, bez vrijednosti lozinke. */
    public String nedostaje() {
        StringBuilder sb = new StringBuilder();
        if (baseUrl == null) sb.append("STR_KP_API_BASE_URL ");
        if (username == null) sb.append("STR_KP_API_USERNAME ");
        if (password == null) sb.append("STR_KP_API_PASSWORD ");
        if (tipPoruke == null || tipPoruke <= 0) sb.append("STR_KP_API_TIP_PORUKE ");
        return sb.toString().strip();
    }

    /** Šifra poruke za vrstu akta: iznimka po aktu, inače zadana. */
    public Integer tipZa(String vrstaAkta) {
        Integer poAktu = vrstaAkta == null ? null : tipPorukePoAktu.get(vrstaAkta);
        return poAktu != null && poAktu > 0 ? poAktu : tipPoruke;
    }

    /** Koliko dugo je poruka „zauzeta" slanjem: dulje od najduljeg mogućeg poziva. */
    public Duration zauzece() {
        return connectTimeout.plus(readTimeout).plusSeconds(30);
    }

    /** Odgoda nakon {@code pokusaja} neuspjelih pokušaja (1 = prvi neuspjeh). */
    public Duration odgoda(int pokusaja) {
        Duration d = backoffBase;
        for (int i = 1; i < pokusaja && d.compareTo(backoffMax) < 0; i++) {
            d = d.multipliedBy(2);
        }
        return d.compareTo(backoffMax) > 0 ? backoffMax : d;
    }

    /** Lozinka se ne ispisuje ni u {@code toString} (log konfiguracije, iznimke pri bindanju). */
    @Override
    public String toString() {
        return "KpApiProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", username=" + username
                + ", password=" + (password == null ? "null" : "***") + ", tipPoruke=" + tipPoruke + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
