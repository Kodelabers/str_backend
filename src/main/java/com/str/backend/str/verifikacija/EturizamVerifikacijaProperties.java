package com.str.backend.str.verifikacija;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Upis zahtjeva za žurnu verifikaciju u eTurizam (T8), {@code str.eturizam.verifikacija.*}.
 *
 * <p>Vrijednosti obavijesti ({@code vrsta}, {@code objekt-tip}, tekst) eTurizam još nije potvrdio
 * ({@code docs/PLAN-T8-T9-ZURNA-VERIFIKACIJA.md} § 6) — zato su konfiguracija, a obavijesti imaju
 * vlastitu zastavicu, zadano ugašenu.
 *
 * @param enabled    upisuje li STR uopće u {@code str.*}; dok je ugašeno, outbox redak nastaje i
 *                   čeka ({@code PENDING})
 * @param korisnik   {@code created_by} / {@code last_modified_by} zahtjeva — oznaka sustava, ne OIB
 * @param timeout    najdulje trajanje jednog pokušaja (transakcija, uključujući čekanje na zaključan
 *                   redak); zaokružuje se na sekunde, najmanje 1 s
 * @param obavijesti obavijesti službenicima nadležne jedinice ({@code str.korisnicka_obavijest})
 * @param retry      ponavljanje neuspjelog upisa ({@link EturizamVerifikacijaRetryJob}); cron se
 *                   čita izravno ({@code str.eturizam.verifikacija.retry.cron})
 */
@ConfigurationProperties("str.eturizam.verifikacija")
public record EturizamVerifikacijaProperties(
        boolean enabled,
        String korisnik,
        Duration timeout,
        Obavijesti obavijesti,
        Retry retry
) {

    public static final String DEFAULT_KORISNIK = "STR";

    public EturizamVerifikacijaProperties {
        korisnik = korisnik == null || korisnik.isBlank() ? DEFAULT_KORISNIK : korisnik.strip();
        timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(10) : timeout;
        obavijesti = obavijesti == null ? new Obavijesti(false, null, null, null) : obavijesti;
        retry = retry == null ? new Retry(0, null, null, 0) : retry;
    }

    /**
     * @param vrsta     {@code korisnicka_obavijest.vrsta} (najviše 64 znaka)
     * @param objektTip {@code korisnicka_obavijest.objekt_tip}; {@code objekt_id} je id zahtjeva
     * @param tekst     predložak teksta; {@code {rn}} = RB, {@code {objekt}} = naziv i mjesto
     *                  objekta (v. {@link VerifikacijaObavijest})
     */
    public record Obavijesti(boolean enabled, String vrsta, String objektTip, String tekst) {

        public static final String DEFAULT_VRSTA = "ZURNA_VERIFIKACIJA";
        public static final String DEFAULT_OBJEKT_TIP = "VERIFICATION_REQUEST";
        public static final String DEFAULT_TEKST = "Smještajnom objektu {objekt} dodijeljen je registracijski "
                + "broj {rn}. Objekt nije verificiran – potrebna je žurna verifikacija.";

        public Obavijesti {
            vrsta = blankToDefault(vrsta, DEFAULT_VRSTA);
            objektTip = blankToDefault(objektTip, DEFAULT_OBJEKT_TIP);
            tekst = blankToDefault(tekst, DEFAULT_TEKST);
        }
    }

    /** Backoff i granica pokušaja istom logikom kao eGOP i mail ({@code EgopRetryPolicy}). */
    public record Retry(int maxAttempts, Duration backoffBase, Duration backoffMax, int batchSize) {

        public Retry {
            maxAttempts = maxAttempts > 0 ? maxAttempts : 10;
            backoffBase = backoffBase != null ? backoffBase : Duration.ofMinutes(5);
            backoffMax = backoffMax != null ? backoffMax : Duration.ofHours(2);
            batchSize = batchSize > 0 ? batchSize : 50;
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
