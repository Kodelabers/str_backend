package com.str.backend.auth.nias;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import com.str.backend.auth.SessionIdentityResolver;
import com.str.backend.auth.dto.MeResponse;
import com.str.backend.categorization.CategorizationDecisionRequest;
import com.str.backend.categorization.CategorizationDecisionResponse;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.lessor.LessorDocumentEntity;
import com.str.backend.lessor.LessorDocumentRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorProfileDto;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnActionResponse;
import com.str.backend.lessor.LessorRnActionService;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.lessor.LessorWithdrawRequest;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.rn.RnRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/nias")
public class NiasController {

    private final NiasOibResolver oibResolver;
    private final RnRepository rnRepository;
    private final LessorRepository lessorRepository;
    private final LessorDocumentRepository lessorDocumentRepository;
    private final CountryRepository countryRepository;
    private final LessorRnActionService rnActionService;
    private final SessionIdentityResolver identityResolver;
    private final NiasFacilityService facilityService;
    private final CategorizationDecisionService categorizationDecisionService;
    private final SubjectProfileService subjectProfileService;
    private final EffectiveOibResolver effectiveOibResolver;
    private final ActingSubjectService actingSubjectService;
    private final ActingSubjectGuard actingSubjectGuard;

    public NiasController(NiasOibResolver oibResolver,
                          RnRepository rnRepository,
                          LessorRepository lessorRepository,
                          LessorDocumentRepository lessorDocumentRepository,
                          CountryRepository countryRepository,
                          LessorRnActionService rnActionService,
                          SessionIdentityResolver identityResolver,
                          NiasFacilityService facilityService,
                          CategorizationDecisionService categorizationDecisionService,
                          SubjectProfileService subjectProfileService,
                          EffectiveOibResolver effectiveOibResolver,
                          ActingSubjectService actingSubjectService,
                          ActingSubjectGuard actingSubjectGuard) {
        this.oibResolver = oibResolver;
        this.rnRepository = rnRepository;
        this.lessorRepository = lessorRepository;
        this.lessorDocumentRepository = lessorDocumentRepository;
        this.countryRepository = countryRepository;
        this.rnActionService = rnActionService;
        this.identityResolver = identityResolver;
        this.facilityService = facilityService;
        this.categorizationDecisionService = categorizationDecisionService;
        this.subjectProfileService = subjectProfileService;
        this.effectiveOibResolver = effectiveOibResolver;
        this.actingSubjectService = actingSubjectService;
        this.actingSubjectGuard = actingSubjectGuard;
    }

    /**
     * Pravna osoba u čije ime korisnik djeluje (e-Zastupanja). OIB tvrtke iz tijela je samo
     * prijedlog: subjekt se prihvaća i sprema u sesiju tek kad ga e-Ovlaštenja potvrde.
     * 400 neispravan OIB ili nepostojeća tvrtka, 403 korisnik nije zastupnik, 401 nevažeća
     * NIAS sjednica, 503 e-Ovlaštenja nedostupna.
     */
    @PostMapping("/acting-subject")
    public ActingSubjectResponse selectActingSubject(@Valid @RequestBody ActingSubjectRequest body,
                                                     Authentication authentication,
                                                     HttpSession session) {
        return ActingSubjectResponse.of(actingSubjectService.select(session, personIdentity(authentication), body.oib()));
    }

    /** Trenutno odabrana pravna osoba; 204 kad korisnik djeluje u svoje ime. */
    @GetMapping("/acting-subject")
    public ResponseEntity<ActingSubjectResponse> actingSubject(Authentication authentication) {
        personOib(authentication);
        return effectiveOibResolver.actingSubject(authentication)
                .map(s -> ResponseEntity.ok(ActingSubjectResponse.of(s)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Povratak na djelovanje u svoje ime. */
    @DeleteMapping("/acting-subject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearActingSubject(Authentication authentication, HttpServletRequest request) {
        personOib(authentication);
        HttpSession session = request.getSession(false);
        if (session != null) {
            actingSubjectService.clear(session);
        }
    }

    /**
     * Podaci o prijavljenom podnositelju za formu zahtjeva za RB: OIB, ime i prezime iz NIAS-a,
     * adresa iz registra (OIB sustav, a dok on nije dostupan {@code str.subject}). Isti izvor
     * koristi izdavanje RB-a ({@link SubjectProfileService}), pa se prikazano i spremljeno ne
     * mogu razići.
     *
     * <p>OIB je uvijek iz sesije, nikad iz parametra. 400 {@code error.subject.notFound} kad ga
     * registar ne poznaje, 503 kad registar nije dostupan.
     *
     * <p>U ime tvrtke iznajmljivač je tvrtka, pa se prebivalište zastupnika ne traži u registru:
     * zastupnik kojeg registar ne poznaje inače bi dobio 400 i ne bi mogao predati zahtjev za
     * tvrtku, iako izdavanje RB-a te podatke ne koristi ({@code SubjectProfileService#toLegalLessor}).
     */
    @GetMapping("/subject")
    public SubjectProfileResponse subject(Authentication authentication) {
        NiasIdentity identity = personIdentity(authentication);
        Optional<ActingSubject> acting = effectiveOibResolver.actingSubject(authentication);
        if (acting.isPresent()) {
            return SubjectProfileResponse.ofRepresentative(acting.get());
        }
        return SubjectProfileResponse.of(
                subjectProfileService.load(identity.oib(), identity.firstName(), identity.lastName()));
    }

    /**
     * Isti unificirani oblik kao {@code /api/auth/me} (delegira na {@link SessionIdentityResolver}).
     * Zadržano radi kompatibilnosti dok se fronta ne prebaci na jedinstveni {@code /api/auth/me}.
     */
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        return identityResolver.resolve(authentication);
    }

    /**
     * Lista RB-ova za prijavljenog NIAS korisnika, identificiranog po OIB-u iz SAML
     * principala. Na local/mock profilu se fallback-a na konfigurirani mock OIB
     * (vidi {@link NiasOibResolver}); seedani su 3 RB-a u changesetu 048. Na dev/cdu
     * bez prave NIAS sesije vraća 401, a kad je sesija aktivna vraća praznu listu
     * dok stvarni podaci ne postanu dostupni.
     */
    @GetMapping("/registrations")
    public List<LessorRnSummaryDto> registrations(Authentication authentication) {
        String oib = resolveOib(authentication);
        return rnRepository.findByLessorOib(oib);
    }

    /**
     * Profil prijavljenog NIAS korisnika — isti DTO kao za non-EU portal, ali se
     * lessor dohvaća po OIB-u iz SAML principala (a ne po LessorPrincipal-u koji
     * postoji samo u username/password flow-u). Vraća 404 ako za OIB ne postoji
     * LessorEntity (NIAS korisnik koji još nije podnio nijednu prijavu).
     */
    @GetMapping("/profile")
    public LessorProfileDto profile(Authentication authentication) {
        String oib = resolveOib(authentication);
        LessorEntity lessor = lessorRepository.findFirstByLessorOibOrderByCreatedAtDesc(oib)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        LessorDocumentEntity doc = lessorDocumentRepository.findByLessorId(lessor.getLessorId()).orElse(null);
        String countryName = null;
        if (lessor.getCountryOfResidenceId() != null) {
            countryName = countryRepository.findById(lessor.getCountryOfResidenceId().longValue())
                    .map(CountryEntity::getName).orElse(null);
        }
        return new LessorProfileDto(
                lessor.getLessorId(),
                lessor.getFirstName(),
                lessor.getLastName(),
                lessor.getEmail(),
                lessor.getMobileNumber(),
                lessor.getTaxNumber(),
                countryName,
                lessor.getApplicationStatus(),
                lessor.getCreatedAt(),
                doc != null ? doc.getDocumentType() : null,
                doc != null ? doc.getDocumentNumber() : null
        );
    }

    /**
     * Popis objekata NIAS korisnika: objekti iz eTurizam registra + uploadana skenirana
     * rješenja koja tamo još nisu upisana. Objekt s RB-om frontend vodi na „Prikaži", objekt
     * bez RB-a na „Zatraži RB".
     *
     * <p>Paginirano — na dev-u postoji iznajmljivač s 1530 objekata.
     */
    @GetMapping("/facilities")
    public FacilityPageResponse facilities(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           Authentication authentication) {
        return facilityService.list(resolveOib(authentication), page, size);
    }

    /**
     * Mjerodavni podaci jednog objekta za predpopunu forme zahtjeva za RB, uz
     * {@code zakljucanaPolja} — popis polja koja se za taj objekt ne smiju mijenjati.
     *
     * <p>Postoji jer tuStart podatke šalje kroz query string koji je korisniku vidljiv i
     * izmjenjiv. Frontend zaključava polja po ovom odgovoru, ne po URL-u; isti račun radi
     * {@code FacilityClaimVerifier} pri submitu, pa se prikaz i provjera ne mogu razići.
     */
    @GetMapping("/facilities/{facilityId}")
    public FacilityClaimResponse facility(@PathVariable String facilityId,
                                          Authentication authentication) {
        return facilityService.claim(resolveOib(authentication), facilityId);
    }

    /**
     * Upload skeniranog papirnatog rješenja o kategorizaciji koje nije migrirano u eTurizam
     * (procedura koju eTurizam već ima). Zapis ide u {@code str_rn.categorization_decision} i
     * do upisa u eTurizam se na popisu iznad prikazuje kao privremeno rješenje, bez RB-a.
     */
    @PostMapping(value = "/categorization-decisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CategorizationDecisionResponse uploadCategorizationDecision(
            @Valid @ModelAttribute CategorizationDecisionRequest req,
            Authentication authentication,
            @RequestHeader(value = ActingSubjectGuard.HEADER, required = false) String actingSubjectHeader) {
        return categorizationDecisionService.upload(ownerOibForChange(authentication, actingSubjectHeader), req);
    }

    /** STR-1.3-001: NIAS user revokes (opoziv) their own RN. Vlasništvo se provjerava
     *  po OIB-u (svaki NIAS submission kreira novi LessorEntity snapshot, pa lessorId
     *  nije stabilan kroz povijest istog korisnika). */
    @PostMapping("/registrations/{rn}/withdraw")
    public LessorRnActionResponse withdrawOwnRegistration(
            @PathVariable String rn,
            @Valid @RequestBody(required = false) LessorWithdrawRequest body,
            Authentication authentication,
            @RequestHeader(value = ActingSubjectGuard.HEADER, required = false) String actingSubjectHeader) {
        String oib = ownerOibForChange(authentication, actingSubjectHeader);
        String reason = body != null ? body.reason() : null;
        return rnActionService.withdrawOwnByOib(rn, oib, reason);
    }

    /**
     * OIB za koji se radi: pravna osoba u čije ime korisnik djeluje, inače on sam
     * ({@link EffectiveOibResolver}). Za sve što je vezano uz vlasnika.
     */
    private String resolveOib(Authentication authentication) {
        return effectiveOibResolver.resolve(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /**
     * Kao {@link #resolveOib}, za radnje koje mijenjaju stanje. Najprije {@link ActingSubjectGuard}:
     * je li radnja pripremljena za subjekta koji je i sada u sesiji (409), bez poziva FINA-i. Zatim se
     * u ime tvrtke zastupanje ponovo potvrđuje — povlačenje RB-a je nepovratno, a odabir može biti star.
     */
    private String ownerOibForChange(Authentication authentication, String actingSubjectHeader) {
        actingSubjectGuard.requireUnchanged(authentication, actingSubjectHeader);
        return effectiveOibResolver.reverifiedActingSubject(authentication)
                .map(ActingSubject::legalOib)
                .orElseGet(() -> resolveOib(authentication));
    }

    /** OIB same prijavljene osobe — za podatke o njoj i za odabir subjekta. */
    private String personOib(Authentication authentication) {
        return oibResolver.resolve(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** Identitet iz assertiona; na local/mock samo mock OIB, bez imena i sjednice. */
    private NiasIdentity personIdentity(Authentication authentication) {
        return NiasOibExtractor.extractIdentity(authentication)
                .orElseGet(() -> new NiasIdentity(personOib(authentication), null, null));
    }
}
