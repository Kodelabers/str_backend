package com.str.backend.lessor;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import com.str.backend.auth.LessorPrincipal;
import com.str.backend.rn.RnRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
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

    public LessorController(LessorRegistrationService registrationService,
                            LessorRepository lessorRepository,
                            LessorDocumentRepository lessorDocumentRepository,
                            CountryRepository countryRepository,
                            RnRepository rnRepository) {
        this.registrationService = registrationService;
        this.lessorRepository = lessorRepository;
        this.lessorDocumentRepository = lessorDocumentRepository;
        this.countryRepository = countryRepository;
        this.rnRepository = rnRepository;
    }

    @PostMapping(value = "/registerLessor", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LessorRegistrationResponse register(
            @Valid @ModelAttribute LessorRegistrationRequest req) throws IOException {
        return registrationService.register(req);
    }

    @GetMapping("/lessor/email-check")
    public ResponseEntity<Void> checkEmail(@RequestParam String email) {
        boolean taken = lessorRepository.findByEmail(email.trim().toLowerCase()).isPresent();
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
                lessor.getTaxNumber(),
                countryName,
                lessor.getApplicationStatus(),
                lessor.getCreatedAt(),
                doc != null ? doc.getDocumentType() : null,
                doc != null ? doc.getDocumentNumber() : null
        );
    }

    @GetMapping("/lessor/registrations")
    public List<LessorRnSummaryDto> getRegistrations(Authentication authentication) {
        UUID lessorId = extractLessorId(authentication);
        return rnRepository.findByLessorId(lessorId);
    }

    private UUID extractLessorId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof LessorPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principal.getLessorId();
    }
}
