package com.str.backend.str.verifikacija;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Upis zahtjeva za žurnu verifikaciju u eTurizam (T8), {@code str.eturizam.verifikacija.*}.
 *
 * <p><b>Zadane vrijednosti žive samo ovdje.</b> {@code application.properties} ključeve veže na env
 * varijable s praznim defaultom ({@code ${STR_…:}}); prazno se veže kao {@code null}, a {@code null}
 * ovdje postaje zadana vrijednost. Zato su komponente omotači ({@code Boolean}, {@code Integer}).
 * Iznimka je {@code retry.cron}, koji čita {@code @Scheduled} i nije dio ovog zapisa.
 *
 * <p>Vrijednosti obavijesti ({@code vrsta}, {@code objekt-tip}, tekst) eTurizam još nije potvrdio
 * ({@code docs/PLAN-T8-T9-ZURNA-VERIFIKACIJA.md} § 6) — zato su konfiguracija, a obavijesti imaju
 * vlastitu zastavicu, zadano ugašenu. Neispravne vrijednosti prijavljuje {@code StartupDiagnostics}.
 *
 * @param enabled       upisuje li STR uopće u {@code str.*}; dok je ugašeno, outbox redak nastaje i
 *                      čeka ({@code PENDING})
 * @param korisnik      {@code created_by} / {@code last_modified_by} zahtjeva — oznaka sustava, ne OIB
 * @param timeout       najdulje trajanje pokušaja iz retry joba i bilježenja neuspjeha (transakcija,
 *                      uključujući čekanje na zaključan redak); zaokružuje se na sekunde, najmanje 1 s
 * @param inlineTimeout isto za prvi pokušaj odmah nakon izdavanja RB-a, koji radi na niti HTTP zahtjeva
 * @param obavijesti    obavijesti službenicima nadležne jedinice ({@code str.korisnicka_obavijest})
 * @param retry         ponavljanje neuspjelog upisa ({@link EturizamVerifikacijaRetryJob})
 */
@ConfigurationProperties("str.eturizam.verifikacija")
public record EturizamVerifikacijaProperties(
        Boolean enabled,
        String korisnik,
        Duration timeout,
        Duration inlineTimeout,
        Obavijesti obavijesti,
        Retry retry
) {

    public static final String DEFAULT_KORISNIK = "STR";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_INLINE_TIMEOUT = Duration.ofSeconds(3);

    public EturizamVerifikacijaProperties {
        enabled = Boolean.TRUE.equals(enabled);
        korisnik = blankToDefault(korisnik, DEFAULT_KORISNIK);
        timeout = positiveOrDefault(timeout, DEFAULT_TIMEOUT);
        inlineTimeout = positiveOrDefault(inlineTimeout, DEFAULT_INLINE_TIMEOUT);
        obavijesti = obavijesti == null ? new Obavijesti(null, null, null, null, null, null) : obavijesti;
        retry = retry == null ? new Retry(null, null, null, null) : retry;
    }

    /**
     * Obavijesti su jedini upis za novi objekt uz predano rješenje: dok su ugašene, njegovi retci
     * čekaju {@code PENDING} (ne zatvaraju se bez obavijesti, kao zahtjev objekta iz eTurizma).
     *
     * @param vrsta           {@code korisnicka_obavijest.vrsta} (najviše 64 znaka), ista za obje vrste retka
     * @param objektTip       {@code korisnicka_obavijest.objekt_tip}; {@code objekt_id} je id zahtjeva. Novi
     *                        objekt zahtjeva nema, pa su ondje oba {@code NULL}
     * @param tekst           predložak teksta za objekt iz eTurizma; {@code {rn}} = RB, {@code {objekt}} =
     *                        naziv, mjesto i id objekta (v. {@link VerifikacijaObavijest})
     * @param maxPrimatelja   najviše obavijesti po retku; iznad toga WARN i samo prvih toliko
     * @param tekstNoviObjekt predložak teksta za novi objekt uz predano rješenje; {@code {rn}} = RB,
     *                        {@code {objekt}} = naziv i mjesto iz zahtjeva za RB
     */
    public record Obavijesti(Boolean enabled, String vrsta, String objektTip, String tekst, Integer maxPrimatelja,
                             String tekstNoviObjekt) {

        public static final String DEFAULT_VRSTA = "ZURNA_VERIFIKACIJA";
        public static final String DEFAULT_OBJEKT_TIP = "VERIFICATION_REQUEST";
        public static final String DEFAULT_TEKST = "Smještajnom objektu {objekt} dodijeljen je registracijski "
                + "broj {rn}. Objekt nije verificiran – potrebna je žurna verifikacija.";
        public static final String DEFAULT_TEKST_NOVI_OBJEKT = "Uz registracijski broj {rn} priloženo je rješenje "
                + "o kategorizaciji za novi smještajni objekt {objekt}. Objekt nije upisan u eTurizam – potrebna "
                + "je žurna verifikacija. Rješenje unesite kroz modul Verifikacije, funkcionalnost Unos rješenja.";
        public static final int DEFAULT_MAX_PRIMATELJA = 200;

        public Obavijesti {
            enabled = Boolean.TRUE.equals(enabled);
            vrsta = blankToDefault(vrsta, DEFAULT_VRSTA);
            objektTip = blankToDefault(objektTip, DEFAULT_OBJEKT_TIP);
            tekst = blankToDefault(tekst, DEFAULT_TEKST);
            maxPrimatelja = maxPrimatelja != null && maxPrimatelja > 0 ? maxPrimatelja : DEFAULT_MAX_PRIMATELJA;
            tekstNoviObjekt = blankToDefault(tekstNoviObjekt, DEFAULT_TEKST_NOVI_OBJEKT);
        }
    }

    /** Backoff i granica pokušaja istom logikom kao eGOP i mail ({@code EgopRetryPolicy}). */
    public record Retry(Integer maxAttempts, Duration backoffBase, Duration backoffMax, Integer batchSize) {

        public Retry {
            maxAttempts = maxAttempts != null && maxAttempts > 0 ? maxAttempts : 10;
            backoffBase = positiveOrDefault(backoffBase, Duration.ofMinutes(5));
            backoffMax = positiveOrDefault(backoffMax, Duration.ofHours(2));
            batchSize = batchSize != null && batchSize > 0 ? batchSize : 50;
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
