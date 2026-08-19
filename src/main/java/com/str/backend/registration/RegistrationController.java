package com.str.backend.registration;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.NiasOibExtractor;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.auth.role.Authorities;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.request.SubmissionEntity;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@Validated
public class RegistrationController {

    private final RegistrationService service;
    private final NiasOibResolver niasOibResolver;

    public RegistrationController(RegistrationService service, NiasOibResolver niasOibResolver) {
        this.service = service;
        this.niasOibResolver = niasOibResolver;
    }

    @PostMapping("/api/generateRegistrationNumber")
    public ResponseEntity<RegistrationResponse> generateRegistrationNumber(
            @Valid @RequestBody RegistrationRequest req,
            Authentication authentication) {
        // When NIAS SAML2 is active, OIB comes from the assertion — override whatever the client sent.
        RegistrationRequest finalReq = NiasOibExtractor.extractOib(authentication)
                .map(oib -> RegistrationRequest.withOib(req, oib))
                .orElse(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.generateRegistrationNumber(finalReq));
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
     * PDF podneska. Sadrži PII (osobni podaci iznajmljivača) pa je owner-scoped:
     * INTERNAL vidi svaki podnesak, USER samo svoj (po lessorId za LOCAL, po OIB-u za NIAS),
     * inače 404 — sprječava IDOR nad nasumičnim {@code submissionId}.
     */
    @GetMapping(value = "/api/generateRegistrationNumber/{submissionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID submissionId, Authentication authentication) {
        SubmissionEntity submission = resolvePdfSubmission(submissionId, authentication);
        String idForName = submission.getFilingNumber() != null
                ? submission.getFilingNumber()
                : submission.getSubmissionId().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"submission-" + idForName.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(submission.getPdfContent());
    }

    /** INTERNAL → bilo koji podnesak; LOCAL → svoj po lessorId; NIAS → svoj po OIB-u; inače 401. */
    private SubmissionEntity resolvePdfSubmission(UUID submissionId, Authentication authentication) {
        if (Authorities.isInternal(authentication)) {
            return service.getSubmissionForPdf(submissionId);
        }
        if (authentication != null && authentication.getPrincipal() instanceof LessorPrincipal principal) {
            return service.getSubmissionForPdfOwnedByLessorId(submissionId, principal.getLessorId());
        }
        // NIAS: pravi SAML principal ili local/mock fallback na nias.mock.fixed-oib.
        String oib = NiasOibExtractor.extractOib(authentication)
                .or(() -> niasOibResolver.resolve(authentication))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return service.getSubmissionForPdfOwnedByOib(submissionId, oib);
    }
}
