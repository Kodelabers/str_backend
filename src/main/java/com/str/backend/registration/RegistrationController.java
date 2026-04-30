package com.str.backend.registration;

import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/generateRegistrationNumber")
@Validated
public class RegistrationController {

    private final RegistrationService service;
    private final SubmissionRepository submissionRepository;

    public RegistrationController(RegistrationService service, SubmissionRepository submissionRepository) {
        this.service = service;
        this.submissionRepository = submissionRepository;
    }

    @PostMapping
    public ResponseEntity<RegistrationResponse> generateRegistrationNumber(@Valid @RequestBody RegistrationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.generateRegistrationNumber(req));
    }

    @GetMapping(value = "/{submissionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID submissionId) {
        SubmissionEntity submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("submission not found: " + submissionId));
        byte[] pdf = submission.getPdfContent();
        if (pdf == null || pdf.length == 0) {
            throw new ResourceNotFoundException("pdf not stored for submission: " + submissionId);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"submission-" + submission.getFilingNumber().replace('/', '_') + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
