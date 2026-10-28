package com.str.backend.lessor;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import com.str.backend.auth.LessorPrincipal;
import com.str.backend.captcha.AltchaService;
import com.str.backend.categorization.CategorizationDecisionRequest;
import com.str.backend.categorization.CategorizationDecisionResponse;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.objection.SuspensionObjectionRequest;
import com.str.backend.objection.SuspensionObjectionResponse;
import com.str.backend.objection.SuspensionObjectionService;
import com.str.backend.registration.ReissueService;
import com.str.backend.registration.RnOwner;
import com.str.backend.registration.dto.ReissuePrefillResponse;
import com.str.backend.rn.RnRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
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

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Validated
public class LessorController {

    private final LessorRegistrationService registrationService;
    private final LessorRepository lessorRepository;
    private final LessorDocumentRepository lessorDocumentRepository;
    private final CountryRepository countryRepository;
    private final RnRepository rnRepository;
    private final LessorRnActionService rnActionService;
    private final AltchaService altchaService;
    private final ReissueService reissueService;
    private final CategorizationDecisionService categorizationDecisionService;
    private final SuspensionObjectionService suspensionObjectionService;

    public LessorController(LessorRegistrationService registrationService,
                            LessorRepository lessorRepository,
                            LessorDocumentRepository lessorDocumentRepository,
                            CountryRepository countryRepository,
                            RnRepository rnRepository,
                            LessorRnActionService rnActionService,
                            AltchaService altchaService,
                            ReissueService reissueService,
                            CategorizationDecisionService categorizationDecisionService,
                            SuspensionObjectionService suspensionObjectionService) {
        this.registrationService = registrationService;
        this.lessorRepository = lessorRepository;
        this.lessorDocumentRepository = lessorDocumentRepository;
        this.countryRepository = countryRepository;
        this.rnRepository = rnRepository;
        this.rnActionService = rnActionService;
        this.altchaService = altchaService;
        this.reissueService = reissueService;
        this.categorizationDecisionService = categorizationDecisionService;
        this.suspensionObjectionService = suspensionObjectionService;
    }

    @PostMapping(value = "/registerLessor", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LessorRegistrationResponse register(
            @Valid @ModelAttribute LessorRegistrationRequest req,
            @RequestHeader(value = "X-Altcha", required = false) String altcha)
            throws IOException {
        altchaService.verifyOrThrow(altcha);
        return registrationService.register(req);
    }

    /**
     * „Je li ovaj e-mail slobodan za otvaranje računa?" — pita se po {@code username}, jer je to
     * prijava non-EU iznajmljivača (postavlja se na lowercase e-mail pri samoregistraciji).
     * E-mail sam po sebi nije jedinstven: NIAS korisnicima je samo kontakt podatak (changeset 065).
     */
    @GetMapping("/lessor/email-check")
    public ResponseEntity<Void> checkEmail(@RequestParam String email) {
        boolean taken = lessorRepository.findByUsername(email.trim().toLowerCase()).isPresent();
        return taken
                ? ResponseEntity.status(HttpStatus.CONFLICT).build()
                : ResponseEntity.ok().build();
    }

    @GetMapping("/lessor/profile")
    public LessorProfileDto getProfile(Authentication authentication) {
        UUID lessorId = extractLessorId(authentication);
        LessorEntity lessor = lessorRepository.findById(lessorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        LessorDocumentEntity doc = lessorDocumentRepository.findByLessorId(lessorId).orElse(null);
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
                lessor.getPhoneNumber(),
                lessor.getContactName(),
                lessor.getTaxNumber(),
                countryName,
                lessor.getApplicationStatus(),
                lessor.getCreatedAt(),
                doc != null ? doc.getDocumentType() : null,
                doc != null ? doc.getDocumentNumber() : null
        );
    }

    /** Popis RB-ova non-EU iznajmljivača, s rokom ponovnog zahtjeva ({@code ponovniZahtjevDo}). */
    @GetMapping("/lessor/registrations")
    public List<LessorRnSummaryDto> getRegistrations(Authentication authentication) {
        UUID lessorId = extractLessorId(authentication);
        return reissueService.withReissueDeadlines(rnRepository.findByLessorId(lessorId));
    }

    /**
     * Predpopuna obrasca za ponovni zahtjev za RB novog objekta nakon povlačenja (T15) — isto kao
     * NIAS {@code GET /api/nias/registrations/{rn}/ponovni-zahtjev}, a vlasnik je iznajmljivač iz
     * prijave ({@code lessorId}). Zahtjev se predaje na
     * {@code POST /api/generateRegistrationNumberExternal} s {@code prethodniRb}.
     */
    @GetMapping("/lessor/registrations/{rn}/ponovni-zahtjev")
    public ReissuePrefillResponse reissuePrefill(@PathVariable String rn, Authentication authentication) {
        return reissueService.prefill(rn, RnOwner.byLessor(extractLessorId(authentication)));
    }

    /**
     * Naknadna predaja rješenja o kategorizaciji non-EU iznajmljivača — isto kao NIAS
     * {@code POST /api/nias/categorization-decisions}, a vlasnik je {@code lessorId} iz prijave.
     * RB je u putanji; što se u tijelu pošalje kao {@code registrationNumber}, ne vrijedi.
     */
    @PostMapping(value = "/lessor/registrations/{rn}/categorization-decision",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CategorizationDecisionResponse uploadCategorizationDecision(
            @PathVariable String rn,
            @ModelAttribute CategorizationDecisionRequest req,
            Authentication authentication) {
        UUID lessorId = extractLessorId(authentication);
        req.setRegistrationNumber(rn);
        return categorizationDecisionService.upload(RnOwner.byLessor(lessorId), req);
    }

    /**
     * Prigovor non-EU iznajmljivača na prijedlog suspenzije ili na suspenziju — isto kao NIAS
     * {@code POST /api/nias/suspension-objections}, a vlasnik je {@code lessorId} iz prijave.
     * RB je u putanji; što se u tijelu pošalje kao {@code registrationNumber}, ne vrijedi.
     */
    @PostMapping(value = "/lessor/registrations/{rn}/suspension-objection",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionObjectionResponse submitSuspensionObjection(
            @PathVariable String rn,
            @ModelAttribute SuspensionObjectionRequest req,
            Authentication authentication) {
        UUID lessorId = extractLessorId(authentication);
        req.setRegistrationNumber(rn);
        return suspensionObjectionService.submit(RnOwner.byLessor(lessorId), "LESSOR:" + lessorId, req);
    }

    /** STR-1.3-001: lessor revokes (opoziv) their own RN → REVOKED. Owner-only. */
    @PostMapping("/lessor/registrations/{rn}/withdraw")
    public LessorRnActionResponse withdrawOwnRegistration(
            @PathVariable String rn,
            @Valid @RequestBody(required = false) LessorWithdrawRequest body,
            Authentication authentication) {
        UUID lessorId = extractLessorId(authentication);
        String reason = body != null ? body.reason() : null;
        return rnActionService.withdrawOwn(rn, lessorId, reason);
    }

    private UUID extractLessorId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof LessorPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principal.getLessorId();
    }
}
