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
import com.str.backend.rn.RnRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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

    public NiasController(NiasOibResolver oibResolver,
                          RnRepository rnRepository,
                          LessorRepository lessorRepository,
                          LessorDocumentRepository lessorDocumentRepository,
                          CountryRepository countryRepository,
                          LessorRnActionService rnActionService,
                          SessionIdentityResolver identityResolver,
                          NiasFacilityService facilityService,
                          CategorizationDecisionService categorizationDecisionService) {
        this.oibResolver = oibResolver;
        this.rnRepository = rnRepository;
        this.lessorRepository = lessorRepository;
        this.lessorDocumentRepository = lessorDocumentRepository;
        this.countryRepository = countryRepository;
        this.rnActionService = rnActionService;
        this.identityResolver = identityResolver;
        this.facilityService = facilityService;
        this.categorizationDecisionService = categorizationDecisionService;
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
     * Upload skeniranog papirnatog rješenja o kategorizaciji koje nije migrirano u eTurizam
     * (procedura koju eTurizam već ima). Zapis ide u {@code str_rn.categorization_decision} i
     * do upisa u eTurizam se na popisu iznad prikazuje kao privremeno rješenje, bez RB-a.
     */
    @PostMapping(value = "/categorization-decisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CategorizationDecisionResponse uploadCategorizationDecision(
            @Valid @ModelAttribute CategorizationDecisionRequest req,
            Authentication authentication) {
        return categorizationDecisionService.upload(resolveOib(authentication), req);
    }

    /** STR-1.3-001: NIAS user revokes (opoziv) their own RN. Vlasništvo se provjerava
     *  po OIB-u (svaki NIAS submission kreira novi LessorEntity snapshot, pa lessorId
     *  nije stabilan kroz povijest istog korisnika). */
    @PostMapping("/registrations/{rn}/withdraw")
    public LessorRnActionResponse withdrawOwnRegistration(
            @PathVariable String rn,
            @Valid @RequestBody(required = false) LessorWithdrawRequest body,
            Authentication authentication) {
        String oib = resolveOib(authentication);
        String reason = body != null ? body.reason() : null;
        return rnActionService.withdrawOwnByOib(rn, oib, reason);
    }

    private String resolveOib(Authentication authentication) {
        return oibResolver.resolve(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
