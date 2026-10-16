package com.str.backend.auth.internal;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.NiasOibExtractor;
import com.str.backend.auth.nias.NiasOibResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prepoznaje internog korisnika (službenik MINTS-a ili županije) i njegove role.
 *
 * <p>OIB se čita isključivo iz NIAS sesije — osobni OIB iz SAML assertiona. Nikad iz zahtjeva i
 * nikad preko {@code EffectiveOibResolver}: tvrtka u čije ime korisnik djeluje ne smije mu dati
 * rolu. Non-EU iznajmljivač prijavljen lozinkom nije interni.
 *
 * <p>Mock OIB ({@code nias.mock.fixed-oib}) vrijedi za <b>svakog</b> posjetitelja, i bez prijave.
 * Zato daje rolu samo uz {@code nias.mock.internal-roles-enabled=true}, koji je uključen samo na
 * {@code local} profilu — na javno dostupnom mock/demo deployu bi inače bilo dovoljno postaviti
 * mock OIB na službenika da cijeli internet dobije back-office.
 *
 * <p>Provjera ide u bazu na svakom pozivu, bez keša, pa oduzeta rola djeluje odmah.
 */
@Service
public class InternalAccessService {

    private static final Logger log = LoggerFactory.getLogger(InternalAccessService.class);

    /** Upravljanje registracijskim brojevima ({@code str.application_role.sys_name}). */
    public static final String RN_MANAGEMENT = "RN_MANAGEMENT";

    /**
     * Obični NIAS korisnik (iznajmljivač): svatko tko nije službenik s {@link #RN_MANAGEMENT} —
     * i interni korisnik bez te role. Nije rola iz sheme {@code str}, nego zadana uloga u STR-u.
     */
    public static final String USER = "USER";

    private static final Pattern OIB = Pattern.compile("\\d{11}");
    private static final String ACTOR_PREFIX = "INTERNAL:";

    private final InternalUserRepository repository;
    private final NiasOibResolver niasOibResolver;
    private final boolean mockRolesEnabled;

    public InternalAccessService(InternalUserRepository repository, NiasOibResolver niasOibResolver,
                                 @Value("${nias.mock.internal-roles-enabled:false}") boolean mockRolesEnabled) {
        this.repository = repository;
        this.niasOibResolver = niasOibResolver;
        this.mockRolesEnabled = mockRolesEnabled;
    }

    /** Role prijavljenog korisnika; prazno za iznajmljivača, neprijavljenog i neispravan OIB. */
    public Set<String> rolesFor(Authentication authentication) {
        return oibOf(authentication).map(repository::findRoles).orElse(Set.of());
    }

    public boolean hasRole(Authentication authentication, String role) {
        return rolesFor(authentication).contains(role);
    }

    /**
     * Uloga NIAS korisnika u STR-u: {@link #RN_MANAGEMENT} za službenika, inače {@link #USER}.
     *
     * <p>Kad se shema {@code str} ne može pročitati, korisnik je {@link #USER}: obični
     * iznajmljivač ne smije ostati bez aplikacije zbog provjere koja se tiče samo službenika.
     * To nije propust — back-office endpointi rolu provjeravaju sami i tada odbijaju
     * ({@link InternalRoleAuthorizationManager}).
     */
    public String effectiveRole(Authentication authentication) {
        try {
            return hasRole(authentication, RN_MANAGEMENT) ? RN_MANAGEMENT : USER;
        } catch (DataAccessException e) {
            log.warn("internal_role_lookup_failed fallback={}", USER, e);
            return USER;
        }
    }

    /**
     * Akter za revizijski trag ({@code INTERNAL:<oib>}). Namjerno ne {@code NIAS:}, koji u
     * {@code registration_number_log} označava radnju iznajmljivača.
     */
    public Optional<String> actorFor(Authentication authentication) {
        return oibOf(authentication).map(oib -> ACTOR_PREFIX + oib);
    }

    /**
     * Prikaz aktera iz revizijskih zapisa: {@code INTERNAL:<oib>} postaje ime i prezime službenika,
     * a kad ga nema (ili je shema {@code str} nedostupna) maskirani OIB — OIB kolege ne smije
     * stajati u sučelju. Akteri drugog oblika (stari zapisi) prikazuju se kakvi jesu.
     */
    public Map<String, String> displayNames(Collection<String> actors) {
        Map<String, String> oibByActor = new HashMap<>();
        for (String actor : actors) {
            if (actor != null && actor.startsWith(ACTOR_PREFIX)) {
                oibByActor.put(actor, actor.substring(ACTOR_PREFIX.length()));
            }
        }
        Map<String, String> names;
        try {
            names = repository.findNames(Set.copyOf(oibByActor.values()));
        } catch (DataAccessException e) {
            log.warn("internal_user_names_unavailable count={}", oibByActor.size(), e);
            names = Map.of();
        }
        Map<String, String> display = new HashMap<>();
        for (String actor : actors) {
            if (actor == null) {
                continue;
            }
            String oib = oibByActor.get(actor);
            display.put(actor, oib == null ? actor : names.getOrDefault(oib, masked(oib)));
        }
        return display;
    }

    private static String masked(String oib) {
        return oib.length() <= 4 ? "***"
                : oib.substring(0, 2) + "*".repeat(oib.length() - 4) + oib.substring(oib.length() - 2);
    }

    private Optional<String> oibOf(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof LessorPrincipal) {
            return Optional.empty();
        }
        Optional<String> oib = NiasOibExtractor.extractOib(authentication).isPresent() || mockRolesEnabled
                ? niasOibResolver.resolve(authentication)
                : Optional.empty();
        return oib.filter(o -> OIB.matcher(o).matches());
    }
}
