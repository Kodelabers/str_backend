package com.str.backend.draft;

import com.str.backend.draft.dto.DraftListItemResponse;
import com.str.backend.draft.dto.DraftRequest;
import com.str.backend.draft.dto.DraftResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/drafts")
public class SubmissionDraftController {

    private final SubmissionDraftService service;
    private final DraftOwnerResolver ownerResolver;

    public SubmissionDraftController(SubmissionDraftService service, DraftOwnerResolver ownerResolver) {
        this.service = service;
        this.ownerResolver = ownerResolver;
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
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        return service.create(ownerResolver.resolve(request, response), body);
    }

    @PutMapping("/{draftId}")
    public DraftListItemResponse update(@PathVariable UUID draftId,
                                        @Valid @RequestBody DraftRequest body,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        return service.update(draftId, ownerResolver.resolve(request, response), body);
    }

    @DeleteMapping("/{draftId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID draftId,
                       HttpServletRequest request,
                       HttpServletResponse response) {
        service.delete(draftId, ownerResolver.resolve(request, response));
    }
}
