package com.str.backend.draft;

import com.str.backend.auth.nias.ActingSubjectGuard;
import com.str.backend.draft.dto.DraftListItemResponse;
import com.str.backend.draft.dto.DraftRequest;
import com.str.backend.draft.dto.DraftResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Nacrti zahtjeva za RB. Izmjene (POST/PUT/DELETE) primaju {@value ActingSubjectGuard#HEADER}: nacrt
 * pripada subjektu u čije ime se djeluje, pa se nacrt pripremljen za sebe ne smije spremiti tvrtki
 * odabranoj u međuvremenu u drugom prozoru (409, {@link ActingSubjectGuard}).
 */
@RestController
@RequestMapping("/api/drafts")
public class SubmissionDraftController {

    private final SubmissionDraftService service;
    private final DraftOwnerResolver ownerResolver;
    private final ActingSubjectGuard actingSubjectGuard;

    public SubmissionDraftController(SubmissionDraftService service, DraftOwnerResolver ownerResolver,
                                     ActingSubjectGuard actingSubjectGuard) {
        this.service = service;
        this.ownerResolver = ownerResolver;
        this.actingSubjectGuard = actingSubjectGuard;
    }

    @GetMapping
    public List<DraftListItemResponse> list(HttpServletRequest request, HttpServletResponse response) {
        return service.list(ownerResolver.resolve(request, response));
    }

    @GetMapping("/{draftId}")
    public DraftResponse get(@PathVariable UUID draftId,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        return service.get(draftId, ownerResolver.resolve(request, response));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DraftListItemResponse create(@Valid @RequestBody DraftRequest body,
                                        Authentication authentication,
                                        @RequestHeader(value = ActingSubjectGuard.HEADER, required = false) String actingSubject,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        actingSubjectGuard.requireUnchanged(authentication, actingSubject);
        return service.create(ownerResolver.resolve(request, response), body);
    }

    @PutMapping("/{draftId}")
    public DraftListItemResponse update(@PathVariable UUID draftId,
                                        @Valid @RequestBody DraftRequest body,
                                        Authentication authentication,
                                        @RequestHeader(value = ActingSubjectGuard.HEADER, required = false) String actingSubject,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        actingSubjectGuard.requireUnchanged(authentication, actingSubject);
        return service.update(draftId, ownerResolver.resolve(request, response), body);
    }

    @DeleteMapping("/{draftId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID draftId,
                       Authentication authentication,
                       @RequestHeader(value = ActingSubjectGuard.HEADER, required = false) String actingSubject,
                       HttpServletRequest request,
                       HttpServletResponse response) {
        actingSubjectGuard.requireUnchanged(authentication, actingSubject);
        service.delete(draftId, ownerResolver.resolve(request, response));
    }
}
