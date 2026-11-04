package com.str.backend.attachment;

import com.str.backend.auth.LessorPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Učitavanje priloga non-EU iznajmljivača (staging); učitavatelj je {@code lessorId} iz prijave. */
@RestController
@RequestMapping("/api/lessor/attachments")
public class LessorPrilogController {

    private final PrilogService service;

    public LessorPrilogController(PrilogService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PrilogResponse upload(@RequestParam(value = "datoteka", required = false) MultipartFile datoteka,
                                 Authentication authentication) {
        return service.stage(ucitao(authentication), datoteka);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        service.deleteStaged(ucitao(authentication), id);
    }

    private static String ucitao(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof LessorPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return Ucitao.lessor(principal.getLessorId());
    }
}
