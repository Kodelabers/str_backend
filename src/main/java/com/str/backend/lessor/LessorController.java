package com.str.backend.lessor;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api")
@Validated
public class LessorController {

    private final LessorRegistrationService registrationService;
    private final LessorRepository lessorRepository;

    public LessorController(LessorRegistrationService registrationService,
                            LessorRepository lessorRepository) {
        this.registrationService = registrationService;
        this.lessorRepository = lessorRepository;
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
}
