package com.str.backend.egop;

import com.str.backend.auth.internal.InternalUserRepository;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Pod kojim se eGOP korisnikom urudžbira akt nakon dodjele.
 *
 * <p>MINT (21.10.2026.): akti u klasi 334-06/11 nose urudžbeni broj službenika koji je radnju
 * napravio. Službenik suspenziju, povlačenje i reaktivaciju radi u STR-u (u revizijskom tragu
 * {@code INTERNAL:<oib>}), pa akt ide pod njegovim eGOP korisnikom iz
 * {@code str.application_user.external_username} (bez domene). Opoziv radi iznajmljivač, a
 * suspenziju po isteku roka sustav — oni idu pod aplikacijskim korisnikom, kao i registracija.
 *
 * <p>Službenik bez upisanog eGOP korisnika: akt ide pod aplikacijskim korisnikom uz ERROR
 * (odluka 21.10.2026.) — urudžba ne smije stati, ali urbroj tada nije službenikov.
 *
 * <p>{@code str.egop.akti.korisnik=APLIKACIJSKI} šalje <b>sve</b> akte pod aplikacijskim
 * korisnikom, bez obzira tko je radnju napravio. Uveden 22.10.2026. kad eGOP na testu nije dao
 * službeniku otvoriti predmet 334-06/11 s nadležnom jedinicom koju vidi aplikacijski korisnik
 * („Odabranu ustrojstvenu jedinicu nije moguće postaviti kao nadležnu…"). Akti tada ne nose
 * urbroj službenika, suprotno uputi MINT-a, pa je default {@code SLUZBENIK}.
 */
@Component
public class EgopAktKorisnik {

    private static final Logger log = LoggerFactory.getLogger(EgopAktKorisnik.class);

    static final String INTERNAL_PREFIX = "INTERNAL:";

    /** Pod čijim se korisnikom urudžbira akt službenika. */
    public enum Nacin { SLUZBENIK, APLIKACIJSKI }

    private final RnLifecycleLookup lookup;
    private final InternalUserRepository internalUsers;
    private final String appUsername;
    private final Nacin nacin;

    public EgopAktKorisnik(RnLifecycleLookup lookup, InternalUserRepository internalUsers,
                           EgopProperties properties,
                           @Value("${str.egop.akti.korisnik:SLUZBENIK}") Nacin nacin) {
        this.lookup = lookup;
        this.internalUsers = internalUsers;
        this.appUsername = properties.qualifiedAppUsername();
        this.nacin = nacin;
        if (nacin == Nacin.APLIKACIJSKI) {
            log.warn("egop_akti_korisnik=APLIKACIJSKI — svi akti nakon dodjele urudžbiraju se pod {},"
                    + " i akti službenika; urbroj nije službenikov", appUsername);
        }
    }

    /** Korisnik za sve eGOP pozive koji stvaraju predmet, pismeno i dokument akta. */
    public String za(EgopPismenoEntity akt) {
        if (nacin == Nacin.APLIKACIJSKI) {
            return appUsername;
        }
        Optional<String> actor = actor(akt);
        if (actor.isEmpty() || !actor.get().startsWith(INTERNAL_PREFIX)) {
            return appUsername;
        }
        String oib = actor.get().substring(INTERNAL_PREFIX.length()).strip();
        try {
            Optional<String> external = internalUsers.findExternalUsername(oib);
            if (external.isPresent()) {
                return external.get();
            }
            log.error("egop_akt_korisnik_fallback reason=no_external_username akt={} rn={} vrsta='{}' — službenik"
                            + " nema eGOP korisnika (str.application_user.external_username); akt se urudžbira"
                            + " pod {} i urbroj neće biti službenikov", akt.getId(), akt.getRn(),
                    akt.getVrstaPismenaNaziv(), appUsername);
        } catch (DataAccessException e) {
            log.error("egop_akt_korisnik_fallback reason=lookup_failed akt={} rn={}: {} — akt se urudžbira pod {}",
                    akt.getId(), akt.getRn(), e.getMessage(), appUsername);
        }
        return appUsername;
    }

    /** Akter prijelaza iz revizijskog traga; {@code actRef} akta je {@code log_id} prijelaza. */
    private Optional<String> actor(EgopPismenoEntity akt) {
        UUID logId;
        try {
            logId = UUID.fromString(akt.getActRef());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        return lookup.findEvent(logId).map(RnLifecycleEvent::actor).filter(a -> !a.isBlank());
    }
}
