package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Akt županijskog službenika (T16): eGOP se ne koristi, službenik upisuje KLASU i URBROJ, a izdavatelj na
 * aktu je njegova županija. Sprema se uz zapis prijelaza ({@code registration_number_log.akt_*}), pa i
 * akt koji nastane kasnije (noćni posao, neuvažavanje očitovanja) zna brojeve i izdavatelja s prijedloga.
 *
 * <p>Izdavatelj je snimak iz eTurizma u trenutku radnje ({@code str.internal_organization},
 * {@code organizational_unit}): kasnija promjena organizacije službenika ne mijenja već izdane akte.
 *
 * @param klasa            KLASA koju je službenik upisao
 * @param urBroj           URBROJ koji je službenik upisao; završava rednim brojem ({@code …-2})
 * @param izdavateljNaziv  naziv županije ({@code internal_organization.name})
 * @param izdavateljOib    OIB županije ({@code internal_organization.pin}); može nedostajati
 * @param izdavateljJedinica naziv službenikove jedinice ({@code organizational_unit.name}); može nedostajati
 * @param izdavateljZuRb   {@code rpj_dgu.zupanije.zu_rb} — po njemu službenici županije šalju zaostale poruke u KP
 */
public record RucniAkt(String klasa, String urBroj, String izdavateljNaziv, String izdavateljOib,
                       String izdavateljJedinica, Integer izdavateljZuRb) {

    public static final int MAX_DULJINA = 64;

    private static final Logger log = LoggerFactory.getLogger(RucniAkt.class);

    /** URBROJ završava rednim brojem pismena u predmetu, npr. {@code 2181-26-2}. */
    private static final Pattern URBROJ = Pattern.compile("^(.*?)(\\d+)$");

    /**
     * KLASA i URBROJ iz zahtjeva, očišćeni i provjereni (400 s prevedenom porukom). Oba su obavezna,
     * najviše {@value #MAX_DULJINA} znakova, bez kontrolnih i nevidljivih znakova (prijelom reda bi razbio
     * zaglavlje akta), a URBROJ mora završavati brojem.
     */
    public static String[] provjeri(String klasa, String urBroj) {
        String k = klasa == null ? null : klasa.strip();
        String u = urBroj == null ? null : urBroj.strip();
        if (k == null || k.isEmpty()) {
            throw new BusinessException("error.rn.akt.klasa.required");
        }
        if (u == null || u.isEmpty()) {
            throw new BusinessException("error.rn.akt.urbroj.required");
        }
        if (k.length() > MAX_DULJINA || u.length() > MAX_DULJINA) {
            throw new BusinessException("error.rn.akt.tooLong");
        }
        if (imaNedopusteneZnakove(k) || imaNedopusteneZnakove(u)) {
            throw new BusinessException("error.rn.akt.invalidChars");
        }
        if (!URBROJ.matcher(u).matches()) {
            throw new BusinessException("error.rn.akt.urbroj.format");
        }
        return new String[]{k, u};
    }

    /**
     * Akt koji slijedi iz zadnjeg prijedloga postupka (istek roka u noćnom poslu, neuvažavanje očitovanja
     * nakon roka), ako je prijedlog bio ručni: KLASA i izdavatelj s prijedloga, URBROJ + 1. Prazno = eGOP.
     */
    public static Optional<RucniAkt> izPrijedloga(RegistrationNumberLogRepository logRepository, String rn,
                                                  RnStatus prijedlog) {
        Optional<RucniAkt> izPrijedloga = logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(rn, prijedlog.name())
                .flatMap(RegistrationNumberLogEntity::rucniAkt);
        if (izPrijedloga.isEmpty()) {
            return Optional.empty();
        }
        RucniAkt p = izPrijedloga.get();
        try {
            RucniAkt sljedeci = p.sljedeci();
            log.info("rucni_akt_izveden rn={} prijedlog={} klasa={} urbroj_prijedloga={} urbroj={} zu_rb={}",
                    rn, prijedlog, sljedeci.klasa(), p.urBroj(), sljedeci.urBroj(), sljedeci.izdavateljZuRb());
            return Optional.of(sljedeci);
        } catch (RuntimeException e) {
            // Ne smije se dogoditi (URBROJ prijedloga je provjeren pri upisu). Bez broja akt ide kroz eGOP — mora se vidjeti.
            log.error("rucni_akt_izvod_pao rn={} prijedlog={} urbroj_prijedloga={} — akt ide kroz eGOP umjesto kao "
                    + "ručni akt županije; provjeriti zapis prijedloga", rn, prijedlog, p.urBroj(), e);
            return Optional.empty();
        }
    }

    /** Kontrolni ({@code Cc}) i formatni ({@code Cf}, npr. bidi) znakovi nisu dio KLASE ni URBROJ-a. */
    private static boolean imaNedopusteneZnakove(String value) {
        return value.codePoints().anyMatch(cp -> {
            int type = Character.getType(cp);
            return type == Character.CONTROL || type == Character.FORMAT;
        });
    }

    /**
     * Akt koji slijedi iz ovog (istek roka nakon prijedloga): ista KLASA i izdavatelj, URBROJ s rednim
     * brojem uvećanim za 1. Vodeće nule se čuvaju ({@code -09} → {@code -10}).
     */
    public RucniAkt sljedeci() {
        Matcher m = URBROJ.matcher(urBroj);
        if (!m.matches()) {
            throw new IllegalStateException("URBROJ bez rednog broja na kraju: " + urBroj);
        }
        String broj = m.group(2);
        String sljedeci = String.valueOf(Long.parseLong(broj) + 1);
        if (sljedeci.length() < broj.length()) {
            sljedeci = "0".repeat(broj.length() - sljedeci.length()) + sljedeci;
        }
        return new RucniAkt(klasa, m.group(1) + sljedeci, izdavateljNaziv, izdavateljOib, izdavateljJedinica,
                izdavateljZuRb);
    }
}
