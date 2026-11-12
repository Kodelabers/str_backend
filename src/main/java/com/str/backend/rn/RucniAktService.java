package com.str.backend.rn;

import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.auth.internal.InternalUserRepository;
import com.str.backend.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Odlučuje ide li akt radnje kroz eGOP ili ga izdaje županijski službenik s ručnom KLASOM i URBROJEM (T16).
 *
 * <ul>
 *   <li>{@link #zaRadnju} — radnja službenika: županijski ({@link CountyScope.Outcome#COUNTY}) mora upisati
 *       KLASU i URBROJ; ministarstvo i neodređen opseg (AMBIGUOUS, ERROR) idu kroz eGOP, a upisani brojevi
 *       se zanemaruju.</li>
 *   <li>akt bez službenika (noćni posao) ili neuvažavanje očitovanja nakon roka izvodi se iz prijedloga
 *       ({@link RucniAkt#izPrijedloga}).</li>
 * </ul>
 */
@Service
public class RucniAktService {

    private static final Logger log = LoggerFactory.getLogger(RucniAktService.class);

    private static final Pattern OIB = Pattern.compile("\\d{11}");
    /** {@code registration_number_log.akt_izdavatelj_naziv} / {@code _jedinica}. */
    private static final int MAX_IZDAVATELJ = 255;

    private final CountyScopeService countyScope;
    private final InternalAccessService internalAccess;
    private final InternalUserRepository internalUsers;

    public RucniAktService(CountyScopeService countyScope, InternalAccessService internalAccess,
                           InternalUserRepository internalUsers) {
        this.countyScope = countyScope;
        this.internalAccess = internalAccess;
        this.internalUsers = internalUsers;
    }

    /**
     * Je li službenik županijski ({@link CountyScope.Outcome#COUNTY}) — isto pravilo po kojem {@link #zaRadnju}
     * traži ručne brojeve, za radnje bez KLASE (npr. neuvažavanje prigovora u županijskom postupku).
     */
    public boolean zupanijskiSluzbenik(Authentication authentication) {
        return zupanijski(countyScope.scopeFor(authentication));
    }

    /**
     * @return ručni akt za županijskog službenika (KLASA i URBROJ provjereni, 400 kad ne valjaju), ili
     *         {@code null} kad akt ide kroz eGOP
     */
    public RucniAkt zaRadnju(Authentication authentication, String klasa, String urBroj) {
        CountyScope scope = countyScope.scopeFor(authentication);
        if (!zupanijski(scope)) {
            if (zadano(klasa) || zadano(urBroj)) {
                // Sučelje je brojeve ponudilo (službenik je bio županijski), a backend ga sada ne vidi tako
                // (promjena organizacije, privremena greška čitanja) — akt ide kroz eGOP. Mora se vidjeti.
                log.warn("rucni_akt_zanemaren outcome={} klasa={} urbroj={} — službenik nije prepoznat kao "
                        + "županijski, akt ide kroz eGOP, a upisani brojevi se ne koriste", scope.outcome(),
                        zaLog(klasa), zaLog(urBroj));
            }
            return null;
        }
        String[] brojevi;
        try {
            brojevi = RucniAkt.provjeri(klasa, urBroj);
        } catch (BusinessException e) {
            // Globalni handler 400 ne bilježi — bez ovoga se ne bi znalo zašto je radnja odbijena.
            log.info("rucni_akt_odbijen zu_rb={} razlog={} klasa={} urbroj={}", scope.zuRb(), e.getMessage(),
                    zaLog(klasa), zaLog(urBroj));
            throw e;
        }
        Optional<InternalUserRepository.Izdavatelj> izdavatelj = internalAccess.officialOib(authentication)
                .flatMap(internalUsers::findIzdavatelj);
        if (izdavatelj.isEmpty()) {
            log.warn("rucni_akt_bez_izdavatelja zu_rb={} — službenik nije u aktivnoj županijskoj organizaciji, "
                    + "na aktu je naziv iz opsega ('{}') bez OIB-a i jedinice", scope.zuRb(), scope.subjectName());
        }
        String naziv = izdavatelj.map(InternalUserRepository.Izdavatelj::naziv).orElse(scope.subjectName());
        String oib = izdavatelj.map(InternalUserRepository.Izdavatelj::oib).map(String::strip).orElse(null);
        if (izdavatelj.isPresent() && (oib == null || !OIB.matcher(oib).matches())) {
            log.warn("rucni_akt_izdavatelj_bez_oib zu_rb={} izdavatelj='{}' pin='{}' — str.internal_organization.pin "
                    + "nije ispravan OIB, akt se izdaje bez OIB-a tijela", scope.zuRb(), naziv, oib);
            oib = null;
        }
        if (naziv == null || naziv.isBlank()) {
            log.warn("rucni_akt_izdavatelj_bez_naziva zu_rb={} — zaglavlje akta bit će bez naziva tijela", scope.zuRb());
        }
        RucniAkt akt = new RucniAkt(brojevi[0], brojevi[1], skrati(naziv), oib,
                skrati(izdavatelj.map(InternalUserRepository.Izdavatelj::jedinica).orElse(null)), scope.zuRb());
        log.info("rucni_akt_zupanija zu_rb={} izdavatelj='{}' klasa={} urbroj={}", akt.izdavateljZuRb(),
                akt.izdavateljNaziv(), akt.klasa(), akt.urBroj());
        return akt;
    }

    private static boolean zupanijski(CountyScope scope) {
        return scope.outcome() == CountyScope.Outcome.COUNTY;
    }

    /** Vrijednost iz zahtjeva za log: bez kontrolnih znakova (lažni redak loga) i skraćena. */
    static String zaLog(String value) {
        if (value == null) {
            return null;
        }
        String cisto = value.codePoints()
                .map(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT ? '?' : cp)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return cisto.length() <= RucniAkt.MAX_DULJINA ? cisto : cisto.substring(0, RucniAkt.MAX_DULJINA) + "…";
    }

    private static boolean zadano(String value) {
        return value != null && !value.isBlank();
    }

    private static String skrati(String value) {
        return value == null || value.length() <= MAX_IZDAVATELJ ? value : value.substring(0, MAX_IZDAVATELJ);
    }
}
