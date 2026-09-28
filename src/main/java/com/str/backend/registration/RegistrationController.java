package com.str.backend.registration;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.NiasIdentity;
import com.str.backend.auth.nias.NiasOibExtractor;
import com.str.backend.captcha.AltchaService;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.request.SubmissionEntity;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@RestController
@Validated
public class RegistrationController {

    private final RegistrationService service;
    private final AltchaService altchaService;
    private final boolean niasEnabled;

    public RegistrationController(RegistrationService service, AltchaService altchaService,
                                  @Value("${nias.saml.enabled:false}") boolean niasEnabled) {
        this.service = service;
        this.altchaService = altchaService;
        this.niasEnabled = niasEnabled;
    }

    /**
     * Izdavanje RB-a za iznajmljivača s OIB-om. Kad je NIAS uključen, OIB, ime i prezime dolaze
     * <b>isključivo</b> iz SAML assertiona.
     *
     * <p>{@code NiasSecurityConfig} traži samo {@code authenticated()}, a non-EU prijava
     * ({@code AuthController.login}) sprema kontekst u istu HTTP sesiju — pa i sesija s
     * {@code LessorPrincipal} prolazi taj lanac. Bez provjere ovdje bi se OIB uzeo iz tijela
     * zahtjeva: samoregistrirani korisnik mogao bi zatražiti RB na tuđi OIB, a backend bi u PDF, koji
     * isti korisnik preuzme, upisao ime i adresu prebivališta te osobe iz registra. Zato 403.
     * Non-EU iznajmljivač ima svoj endpoint ({@code /api/generateRegistrationNumberExternal}).
     *
     * <p>Bez NIAS-a (local/mock) OIB ostaje iz tijela — tako se lokalno testiraju razni iznajmljivači.
     */
    @PostMapping("/api/generateRegistrationNumber")
    public ResponseEntity<RegistrationResponse> generateRegistrationNumber(
            @Valid @RequestBody RegistrationRequest req,
            Authentication authentication,
            @RequestHeader(value = "X-Altcha", required = false) String altcha) {
        Optional<NiasIdentity> identity = NiasOibExtractor.extractIdentity(authentication);
        if (niasEnabled && identity.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Zahtjev za registracijski broj s OIB-om traži NIAS prijavu.");
        }
        altchaService.verifyOrThrow(altcha);
        RegistrationRequest finalReq = identity
                .map(id -> RegistrationRequest.withOib(req, id.oib()))
                .orElse(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.generateRegistrationNumber(finalReq, identity.orElse(null)));
    }

    @PostMapping("/api/generateRegistrationNumberExternal")
    public ResponseEntity<RegistrationResponse> generateRegistrationNumberExternal(
            @Valid @RequestBody RegistrationExternalRequest req,
            @AuthenticationPrincipal LessorPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.generateRegistrationNumberExternal(req, principal.getLessorId()));
    }

    /**
     * PDF zahtjeva — samo vlasniku (v. {@link RegistrationService#getSubmissionForPdf}). Do sada
     * ga je štitila samo neprobojnost UUID-a.
     */
    @GetMapping(value = "/api/generateRegistrationNumber/{submissionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID submissionId, Authentication authentication) {
        SubmissionEntity submission = service.getSubmissionForPdf(submissionId, requester(authentication));
        String idForName = submission.getFilingNumber() != null
                ? submission.getFilingNumber()
                : submission.getSubmissionId().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"submission-" + idForName.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(submission.getPdfContent());
    }

    private SubmissionRequester requester(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof LessorPrincipal principal) {
            return SubmissionRequester.lessor(principal.getLessorId());
        }
        Optional<String> oib = NiasOibExtractor.extractOib(authentication);
        if (oib.isPresent()) {
            return SubmissionRequester.oib(oib.get());
        }
        if (niasEnabled) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return SubmissionRequester.ANYONE;
    }
}
