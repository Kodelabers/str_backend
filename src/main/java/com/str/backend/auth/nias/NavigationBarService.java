package com.str.backend.auth.nias;

import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * FINA-ina navigacijska traka e-Građani kao izbornik subjekta u čije ime osoba djeluje.
 *
 * <p><b>Popis</b> subjekata traka dohvaća sama, u pregledniku: NIAS joj pri prijavi preda
 * autentifikacijske podatke i vrati nam {@code nav_token} (NIAS specifikacija, korak 12), a traka se
 * s prijavom uparuje preko {@code navToken} i {@code messageId} ({@code InResponseTo} SAML odgovora).
 * Certifikat usluge tu ne sudjeluje — zato traka radi i bez pristupa {@code GetNavigationData}.
 *
 * <p><b>Odabir</b> traka vraća punom navigacijom na {@code change_entity_url}, koji joj šaljemo mi
 * (nije vezan uz registraciju usluge). Parametri su nepotpisani i samo predlažu: subjekt se sprema
 * tek nakon potpisane provjere ({@link ActingSubjectService#select}), s istim ograničenjem broja
 * odabira kao {@code POST /api/nias/acting-subject}. {@code state} (slučajan, vezan uz sesiju, jednokratan)
 * sprječava da tuđa poveznica promijeni subjekt korisniku koji ga nije odabrao u traci: vrijednost
 * putuje FINA-i u adresi skripte, pa se nakon prvog povratka zamjenjuje novom.
 *
 * <p>Traka pokazuje i subjekte koje ne prihvaćamo (e-Punomoći, obrt, tijela) — takav odabir završi
 * porukom, a ranije odabrani subjekt ostaje.
 */
@Service
public class NavigationBarService {

    static final String STATE_KEY = NavigationBarService.class.getName() + ".STATE";
    /** Oznaka da je odabir subjekta u ovoj prijavi već ponuđen; nova prijava je briše ({@link NiasSamlConfig}). */
    static final String ENTITY_SEARCH_SHOWN_KEY = NavigationBarService.class.getName() + ".ENTITY_SEARCH_SHOWN";
    static final String CHANGE_ENTITY_PATH = "/api/nias/acting-subject/change-entity";
    private static final String LOGIN_PATH = "/saml2/authenticate/nias";
    private static final String ATTR_NAV_TOKEN = "nav_token";
    /** IZVOR_REG sudskog registra — jedini koji prihvaćamo (samo tvrtke, v. docs/EOVLASTENJA.md). */
    private static final String COMPANY_REGISTRY = "1";

    private static final Logger log = LoggerFactory.getLogger(NavigationBarService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Ishod odabira u traci, kao {@code ?entitySwitch=} na povratku na frontend. */
    public enum Outcome {
        OK("ok"),
        SELF("self"),
        NOT_REPRESENTATIVE("notRepresentative"),
        NOT_FOUND("notFound"),
        UNSUPPORTED("unsupported"),
        INVALID("invalid"),
        SESSION_EXPIRED("sessionExpired"),
        LOGIN_REQUIRED("loginRequired"),
        RATE_LIMITED("rateLimited"),
        UNAVAILABLE("unavailable");

        private final String param;

        Outcome(String param) {
            this.param = param;
        }

        public String param() {
            return param;
        }
    }

    private final NavigationBarProperties props;
    private final ActingSubjectService actingSubjectService;

    public NavigationBarService(NavigationBarProperties props, ActingSubjectService actingSubjectService) {
        props.requireComplete();
        this.props = props;
        this.actingSubjectService = actingSubjectService;
    }

    /**
     * Adresa skripte trake za prijavljenu osobu; prazno kad je traka isključena ili prijava ne nosi
     * {@code nav_token} / {@code InResponseTo} (tada traka ne bi znala tko je prijavljen).
     */
    public Optional<String> scriptUrl(HttpSession session, Authentication authentication) {
        if (!props.enabled() || !(authentication instanceof Saml2Authentication saml)) {
            return Optional.empty();
        }
        Optional<NiasIdentity> person = NiasOibExtractor.extractIdentity(authentication);
        String navToken = navToken(saml);
        String messageId = NiasSecurityUtil.inResponseToOf(saml.getSaml2Response());
        if (person.isEmpty() || navToken == null || messageId == null) {
            log.info("navigation_bar unavailable nav_token={} message_id={}",
                    navToken != null ? "da" : "nema", messageId != null ? "da" : "nema");
            return Optional.empty();
        }
        String base = props.publicBaseUrl();
        Optional<ActingSubject> acting = actingSubjectService.current(session, person.get().oib());

        // Nazivi i oblik parametara kao u FINA primjeru; vrijednosti se kodiraju cijele (i & unutar
        // change_entity_url), a {ToLegalIps} i sl. traka zamjenjuje odabranim subjektom.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("login_url", base + LOGIN_PATH);
        // Odjavu i prijavu iz trake frontend presreće (SLO je POST); adrese su za slučaj da ne uspije.
        params.put("logout_url", base + "/");
        params.put("messageId", messageId);
        params.put("navToken", navToken);
        params.put("change_entity_url", base + CHANGE_ENTITY_PATH
                + "?toLegalIps={ToLegalIps}&toLegalIzvorReg={ToLegalIzvor_reg}&forPersonOib={ForPersonOib}"
                + "&state=" + state(session));
        params.put("ToLegalIps", acting.map(ActingSubject::legalOib).orElse(""));
        params.put("ToLegalIzvor_reg", acting.isPresent() ? COMPANY_REGISTRY : "");
        params.put("show_entities", "True");
        params.put("show_entity_search", promptEntitySearch(session, acting.isPresent()) ? "True" : "False");
        params.put("language", "hr");

        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return Optional.of(props.scriptUrl() + (props.scriptUrl().contains("?") ? "&" : "?") + query);
    }

    /**
     * Povratak iz trake nakon odabira. Uvijek vraća adresu frontenda s ishodom — traka ovamo dolazi
     * punom navigacijom preglednika, pa JSON ili stranica greške ne bi imali tko prikazati.
     *
     * @param toLegalIps   OIB odabrane pravne osobe; prazan (ili OIB same osobe) znači fizičku osobu
     * @param forPersonOib odabrana fizička osoba kad {@code toLegalIps} nema — „u svoje ime" samo kad je to
     *                     sama prijavljena osoba (ili prazno); tuđa osoba (e-Punomoć) ne briše odabir
     */
    public URI change(HttpServletRequest request, Authentication authentication,
                      String toLegalIps, String toLegalIzvorReg, String forPersonOib, String state) {
        Outcome outcome = decide(request, authentication, trim(toLegalIps), trim(toLegalIzvorReg),
                trim(forPersonOib), state);
        log.info("navigation_bar change outcome={} izvor_reg={}", outcome.param(),
                toLegalIzvorReg == null || toLegalIzvorReg.isBlank() ? "-" : safe(toLegalIzvorReg));
        return URI.create(props.returnUrl() + (props.returnUrl().contains("?") ? "&" : "?")
                + "entitySwitch=" + outcome.param());
    }

    private Outcome decide(HttpServletRequest request, Authentication authentication,
                           String legalIps, String izvorReg, String forPersonOib, String state) {
        HttpSession session = request.getSession(false);
        Optional<NiasIdentity> person = NiasOibExtractor.extractIdentity(authentication);
        if (session == null || person.isEmpty()) {
            return Outcome.LOGIN_REQUIRED;
        }
        if (!stateMatches(session, state)) {
            log.warn("navigation_bar change invalid_state — odabir nije došao iz naše trake u ovoj sesiji");
            return Outcome.INVALID;
        }
        // Jednokratan: sljedeća adresa skripte (nakon povratka stranica se učitava iznova) nosi novi.
        session.removeAttribute(STATE_KEY);
        String self = person.get().oib();
        if (legalIps == null || legalIps.equals(self)) {
            if (forPersonOib != null && !forPersonOib.equals(self)) {
                return Outcome.UNSUPPORTED;   // druga fizička osoba (e-Punomoć) — ne podržavamo
            }
            actingSubjectService.clear(session);
            return Outcome.SELF;
        }
        if (izvorReg != null && !COMPANY_REGISTRY.equals(izvorReg)) {
            return Outcome.UNSUPPORTED;
        }
        try {
            actingSubjectService.select(session, person.get(), legalIps);
            return Outcome.OK;
        } catch (BusinessException e) {
            return Outcome.INVALID;
        } catch (ActingSubjectRateLimitException e) {
            return Outcome.RATE_LIMITED;
        } catch (EOvlastenjaException e) {
            return switch (e.reason()) {
                case SESSION -> Outcome.SESSION_EXPIRED;
                case NOT_REPRESENTATIVE -> Outcome.NOT_REPRESENTATIVE;
                case SUBJECT_NOT_FOUND -> Outcome.NOT_FOUND;
            };
        } catch (ExternalRegistryException e) {
            return Outcome.UNAVAILABLE;
        } catch (RuntimeException e) {
            // Širok namjerno: preglednik mora dobiti preusmjerenje, ne stranicu greške 500.
            log.warn("navigation_bar change failed", e);
            return Outcome.UNAVAILABLE;
        }
    }

    /**
     * {@code show_entity_search=True} nije prikaz pretrage nego naredba: traka tada pri svakom učitavanju
     * sama otvori odabir subjekta ({@code showEntitySearch()}). Svaki odabir se vraća punim učitavanjem
     * stranice, pa bi se dijalog otvarao iznova i skrivao poruku o ishodu. Nudi se zato jednom po prijavi,
     * i to samo dok subjekt nije odabran; poslije je odabir u traci pod „Promjena subjekta".
     */
    private static boolean promptEntitySearch(HttpSession session, boolean subjectSelected) {
        if (session.getAttribute(ENTITY_SEARCH_SHOWN_KEY) != null) {
            return false;
        }
        // I kad je subjekt već odabran: inače bi ga povratak u svoje ime kasnije ponovno otvorio.
        session.setAttribute(ENTITY_SEARCH_SHOWN_KEY, Boolean.TRUE);
        return !subjectSelected;
    }

    /** Slučajna vrijednost po sesiji; nova prijava je briše ({@link NiasSamlConfig}). */
    private static String state(HttpSession session) {
        if (session.getAttribute(STATE_KEY) instanceof String existing) {
            return existing;
        }
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(STATE_KEY, state);
        return state;
    }

    private static boolean stateMatches(HttpSession session, String state) {
        return session.getAttribute(STATE_KEY) instanceof String expected
                && state != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8));
    }

    private static String navToken(Saml2Authentication saml) {
        if (saml.getPrincipal() instanceof Saml2AuthenticatedPrincipal p) {
            Object value = p.getFirstAttribute(ATTR_NAV_TOKEN);
            return value == null ? null : trim(value.toString());
        }
        return null;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    /** Nepotpisana vrijednost iz URL-a ide u log samo kao kratka znamenka. */
    private static String safe(String value) {
        String t = value.trim();
        return t.matches("\\d{1,2}") ? t : "?";
    }
}
