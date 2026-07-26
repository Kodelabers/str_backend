package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.dto.RnResponse;
import com.str.backend.rn.dto.RnSummaryDto;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/rn")
public class RnController {

    private final RnService service;
    private final RnMapper mapper;
    private final StrDocumentService documentService;
    private final RnRepository rnRepository;

    public RnController(RnService service, RnMapper mapper, StrDocumentService documentService,
                        RnRepository rnRepository) {
        this.service = service;
        this.mapper = mapper;
        this.documentService = documentService;
        this.rnRepository = rnRepository;
    }

    /** STR-1.5: display of inactive RNs (SUSPENDED + WITHDRAWN). */
    @GetMapping("/inactive")
    public List<RnResponse> inactive() {
        return mapper.toResponseList(service.inactive());
    }

    /** STR wireframe §12 / §13: paginated public registry of RNs. */
    @GetMapping
    public Page<RnSummaryDto> registry(
            @RequestParam(defaultValue = "ACTIVE") RnRegistryView view,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String street,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String lessor,
            @PageableDefault(size = 20, sort = "issueDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.searchRegistry(view, q, county, typeId, rb, city, street, name, lessor, pageable);
    }

    /** STR wireframe §12 / §13: full detail of a single RN (accommodation + lessor). */
    @GetMapping("/{rn}/detail")
    public RnDetailDto detail(@PathVariable String rn) {
        return service.detail(rn);
    }

    @GetMapping("/{rn}")
    public RnResponse get(@PathVariable String rn) {
        return mapper.toResponse(service.find(rn));
    }

    @PostMapping("/{rn}/suspend")
    public RnResponse suspend(
            @PathVariable String rn,
            @RequestParam RnTrigger reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate suspensionDeadline) {
        return mapper.toResponse(service.suspend(rn, reason, suspensionDeadline));
    }

    @PostMapping("/{rn}/reactivate")
    public RnResponse reactivate(@PathVariable String rn) {
        return mapper.toResponse(service.reactivate(rn));
    }

    @PostMapping("/{rn}/withdraw")
    public RnResponse withdraw(@PathVariable String rn,
                              @RequestParam(required = false) String reason) {
        return mapper.toResponse(service.withdraw(rn, reason));
    }

    /**
     * STR-2.1: generira akt životnog ciklusa RB-a kao PDF, po strukturi čl. 98. ZUP-a.
     * Dostava u korisnički pretinac i urudžbiranje u eGOP su zasebni koraci — ovaj endpoint
     * samo vraća dokument (vidi {@link StrDocumentService}).
     *
     * <p>Akt nosi osobne podatke stranke, uključujući OIB koji čl. 98. st. 2 traži u uvodu, pa
     * je zaštićen dvostruko: {@code SecurityConfig} traži prijavu, a ovdje se iznajmljivača
     * ograničava na vlastite registracijske brojeve. Kad stignu interne role (BX0), voditelj
     * postupka prolazi kroz istu granu kao svaki ne-iznajmljivač.
     */
    @GetMapping("/{rn}/documents/{tip}")
    public ResponseEntity<byte[]> document(
            @PathVariable @Pattern(regexp = RegistrationNumber.REGEXP) String rn,
            @PathVariable String tip,
            @RequestParam(required = false) String reason,
            Authentication authentication) {
        StrDocumentType type = StrDocumentType.fromSlug(tip)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Nepoznata vrsta akta: " + tip));
        requireAccess(rn, authentication);
        byte[] pdf = documentService.render(type, rn, reason);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + type.slug() + "-" + rn + ".pdf\"")
                .body(pdf);
    }

    /**
     * Prijavljeni iznajmljivač smije preuzeti samo akte koji se odnose na njegove RB-ove —
     * on im je i adresat. Tuđi RB se prijavljuje kao 404, da endpoint ne otkriva postojanje.
     */
    private void requireAccess(String rn, Authentication authentication) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (authentication.getPrincipal() instanceof LessorPrincipal principal
                && !rnRepository.isOwnedByLessor(rn, principal.getLessorId())) {
            throw new ResourceNotFoundException("rn not found: " + rn);
        }
    }
}
