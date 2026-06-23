package com.str.backend.registration;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.NiasOibExtractor;
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

    public RegistrationController(RegistrationService service) {
        this.service = service;
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

    @GetMapping(value = "/api/generateRegistrationNumber/{submissionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID submissionId) {
        SubmissionEntity submission = service.getSubmissionForPdf(submissionId);
        String idForName = submission.getFilingNumber() != null
                ? submission.getFilingNumber()
                : submission.getSubmissionId().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"submission-" + idForName.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(submission.getPdfContent());
    }
}
