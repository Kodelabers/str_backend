package com.str.backend.kp.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Slanje u korisnički pretinac (KP) preko eTurizmova KP API-ja ({@code str-internal-api/kp-api}),
 * {@code str.kp-api.*}. Koristi se za akte županijskih službenika, koji ne idu kroz eGOP (T16).
 *
 * <p><b>Prijava je isključivo NIAS sesijom</b> službenika (zajednička tablica sesija sa str-internal-api),
 * bez korisničkog imena i lozinke. Zato se šalje samo dok postoji sesija: odmah nakon radnje službenika,
 * a ono što tada ne prođe (ili nastane bez sesije, npr. noćni posao) pri sljedećem zahtjevu službenika iste
 * županije ({@link KpZaostaleInterceptor}).
 *
 * <p>Zadane vrijednosti žive samo ovdje; {@code application.properties} ključeve veže na env s praznim
 * defaultom, a prazno znači „zadano". Šifra poruke ({@code tip-poruke}) i adresa razlikuju se po okolini
 * (test 1741, produkcija 2081), pa ih profil daje kroz env ({@code application-cdu.properties},
 * {@code application-prod.properties}).
 *
 * @param enabled         šalje li STR uopće u KP API; ugašeno (zadano) znači samo e-pošta
 * @param baseUrl         npr. {@code https://et2-test-internal-eturizam.gov.hr/str-internal-api/kp-api}
 * @param tipPoruke       ID tipa poruke iz Registra e-poruka (APIS-IT), zadani za sve akte
 * @param tipPorukePoAktu iznimke po vrsti akta ({@code StrDocumentType} ime), npr. {@code SUSPENZIJA: 1843}
 * @param maxVelicina     najviše bajtova po poruci (tekst + privici u base64); KP dopušta 3 MB
 * @param maxPoZahtjevu   najviše zaostalih poruka koje jedan zahtjev službenika pokrene
 * @param maxAttempts     neuspjelih pokušaja (502, timeout, veza) prije odustajanja
 * @param backoffBase     odgoda nakon prvog neuspjeha; svaki sljedeći udvostručuje, do {@code backoffMax}
 * @param backoffMax      najdulja odgoda
 * @param connectTimeout  spajanje na KP API
 * @param readTimeout     odgovor KP API-ja (sinkron: čeka odgovor KP-a)
 * @param poolSize        niti za slanje izvan niti zahtjeva
 * @param queueCapacity   red zadataka; što ne stane, šalje se pri sljedećem zahtjevu
 */
@ConfigurationProperties("str.kp-api")
public record KpApiProperties(
        Boolean enabled,
        String baseUrl,
        Integer tipPoruke,
        Map<String, Integer> tipPorukePoAktu,
        Long maxVelicina,
        Integer maxPoZahtjevu,
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
        baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.strip();
        tipPorukePoAktu = tipPorukePoAktu == null ? Map.of() : Map.copyOf(tipPorukePoAktu);
        maxVelicina = maxVelicina != null && maxVelicina > 0 ? maxVelicina : DEFAULT_MAX_VELICINA;
        maxPoZahtjevu = maxPoZahtjevu != null && maxPoZahtjevu > 0 ? maxPoZahtjevu : 5;
        maxAttempts = maxAttempts != null && maxAttempts > 0 ? maxAttempts : 10;
        backoffBase = positiveOrDefault(backoffBase, Duration.ofMinutes(5));
        backoffMax = positiveOrDefault(backoffMax, Duration.ofHours(2));
        connectTimeout = positiveOrDefault(connectTimeout, Duration.ofSeconds(5));
        readTimeout = positiveOrDefault(readTimeout, Duration.ofSeconds(30));
        poolSize = poolSize != null && poolSize > 0 ? poolSize : 2;
        queueCapacity = queueCapacity != null && queueCapacity > 0 ? queueCapacity : 50;
    }

    /** Slanje je uključeno i potpuno konfigurirano (adresa i šifra poruke). */
    public boolean aktivno() {
        return enabled && baseUrl != null && tipPoruke != null && tipPoruke > 0;
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

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
