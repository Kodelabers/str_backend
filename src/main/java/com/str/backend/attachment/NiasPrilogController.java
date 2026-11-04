package com.str.backend.attachment;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.ActingSubjectGuard;
import com.str.backend.auth.nias.EffectiveOibResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Učitavanje priloga NIAS iznajmljivača (staging) — prilog čeka radnju koja ga veže ({@code prilogIds}).
 *
 * <p>Učitavatelj je <b>vlasnik</b> za kojeg se radi ({@link EffectiveOibResolver}): tvrtka kad osoba
 * djeluje u njezino ime. Zaglavlje {@value ActingSubjectGuard#HEADER} se provjerava kao kod uploada
 * rješenja ({@link ActingSubjectGuard}, 409 kad je subjekt u međuvremenu promijenjen); zastupanje se
 * kroz e-Ovlaštenja ponovo potvrđuje tek radnjom koja prilog veže — učitavanje ne mijenja spis.
 */
@RestController
@RequestMapping("/api/nias/attachments")
public class NiasPrilogController {

    private final PrilogService service;
    private final EffectiveOibResolver effectiveOibResolver;
    private final ActingSubjectGuard actingSubjectGuard;

    public NiasPrilogController(PrilogService service, EffectiveOibResolver effectiveOibResolver,
                                ActingSubjectGuard actingSubjectGuard) {
        this.service = service;
        this.effectiveOibResolver = effectiveOibResolver;
        this.actingSubjectGuard = actingSubjectGuard;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PrilogResponse upload(@RequestParam(value = "datoteka", required = false) MultipartFile datoteka,
                                 Authentication authentication,
                                 @RequestHeader(value = ActingSubjectGuard.HEADER, required = false) String actingSubject) {
        return service.stage(ucitao(authentication, actingSubject), datoteka);
    }

    /** Briše vlastiti nevezani prilog (maknut s forme prije slanja). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication,
                       @RequestHeader(value = ActingSubjectGuard.HEADER, required = false) String actingSubject) {
        service.deleteStaged(ucitao(authentication, actingSubject), id);
    }

    private String ucitao(Authentication authentication, String actingSubject) {
        // Non-EU sesija prolazi i NIAS lanac; ona učitava kroz /api/lessor/attachments.
        if (authentication != null && authentication.getPrincipal() instanceof LessorPrincipal) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        actingSubjectGuard.requireUnchanged(authentication, actingSubject);
        String oib = effectiveOibResolver.resolve(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return Ucitao.nias(oib);
    }
}
