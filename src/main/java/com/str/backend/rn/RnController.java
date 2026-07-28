package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.NiasOibExtractor;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.dto.RnDocumentDto;
import com.str.backend.rn.dto.RnResponse;
import com.str.backend.rn.dto.RnSummaryDto;
import com.str.backend.statistics.StatisticsExportService;
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
import java.util.Optional;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/rn")
public class RnController {

    private final RnService service;
    private final RnMapper mapper;
    private final StrDocumentService documentService;
    private final RnDocumentsService documentsService;
    private final RnRepository rnRepository;
    private final StatisticsExportService exportService;

    public RnController(RnService service, RnMapper mapper, StrDocumentService documentService,
                        RnDocumentsService documentsService, RnRepository rnRepository,
                        StatisticsExportService exportService) {
        this.service = service;
        this.mapper = mapper;
        this.documentService = documentService;
        this.documentsService = documentsService;
        this.rnRepository = rnRepository;
        this.exportService = exportService;
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
            @RequestParam(required = false) String municipality,
            @RequestParam(required = false) Long typeId,
            @RequestParam(defaultValue = "false") boolean foreignOnly,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String street,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String lessor,
            @PageableDefault(size = 20, sort = "issueDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.searchRegistry(view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor, pageable);
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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate suspensionDeadline,
            @RequestParam(required = false) String note) {
        return mapper.toResponse(service.suspend(rn, reason, suspensionDeadline, note));
    }

    @PostMapping("/{rn}/revoke-proposal")
    public RnResponse revokeProposal(@PathVariable String rn) {
        return mapper.toResponse(service.revokeProposal(rn));
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
     * Popis svih dokumenata vezanih uz RB (zahtjev + obavijest o dodjeli + akti životnog
     * ciklusa), za prikaz „Moji registracijski brojevi". Svaki unos nosi {@code href} za
     * preuzimanje. Owner-scoped kao i pojedinačni download.
     */
    @GetMapping("/{rn}/documents")
    public List<RnDocumentDto> documents(
            @PathVariable @Pattern(regexp = RegistrationNumber.REGEXP) String rn,
            Authentication authentication) {
        requireAccess(rn, authentication);
        return documentsService.listForRn(rn);
    }

    /**
     * STR-2.1: generira akt životnog ciklusa RB-a kao PDF, po strukturi čl. 98. ZUP-a.
     * Dostava u korisnički pretinac i urudžbiranje u eGOP su zasebni koraci — ovaj endpoint
     * samo vraća dokument (vidi {@link StrDocumentService}). {@code tip=zahtjev} nema predložak,
     * pa se servira pohranjeni podnesak sa submissiona.
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
        byte[] pdf = switch (type) {
            // Zahtjev nema predložak — servira se pohranjeni podnesak sa submissiona.
            case ZAHTJEV -> documentsService.zahtjevPdf(rn);
            // Dodjela se renderira s pravim URBROJ-em izlaznog pismena (kao verzija u eGOP-u).
            case DODJELA -> documentService.render(type, rn, reason, documentsService.dodjelaFiling(rn));
            default -> documentService.render(type, rn, reason);
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + type.slug() + "-" + rn + ".pdf\"")
                .body(pdf);
    }

    /**
     * Preuzimanje pohranjenog akta životnog ciklusa po id-u ({@code egop_pismeno}). Vraća
     * vjerodostojan original — akte suspenzijskog toka ne renderiramo iznova jer bi re-render
     * dao drugačiji sadržaj (obrisan rok, promijenjen razlog). Akt tuđeg/nepostojećeg RB-a → 404.
     */
    @GetMapping("/{rn}/documents/pohranjeno/{aktId}")
    public ResponseEntity<byte[]> storedDocument(
            @PathVariable @Pattern(regexp = RegistrationNumber.REGEXP) String rn,
            @PathVariable UUID aktId,
            Authentication authentication) {
        requireAccess(rn, authentication);
        RnDocumentsService.StoredDocument doc = documentsService.storedAktPdf(rn, aktId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + doc.filename() + "\"")
                .body(doc.pdf());
    }

    /** STR §12/§13: export filtered registry as Excel. */
    @GetMapping("/export/xlsx")
    public ResponseEntity<byte[]> exportXlsx(
            @RequestParam(defaultValue = "ALL") RnRegistryView view,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) String municipality,
            @RequestParam(required = false) Long typeId,
            @RequestParam(defaultValue = "false") boolean foreignOnly,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String street,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String lessor) {
        byte[] xlsx = exportService.generateRegistryXlsx(
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"registar-rb.xlsx\"")
                .body(xlsx);
    }

    /** STR §12/§13: export filtered registry as CSV. */
    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(defaultValue = "ALL") RnRegistryView view,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) String municipality,
            @RequestParam(required = false) Long typeId,
            @RequestParam(defaultValue = "false") boolean foreignOnly,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String street,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String lessor) {
        byte[] csv = exportService.generateRegistryCsv(
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"registar-rb.csv\"")
                .body(csv);
    }

    /** STR §12/§13: export filtered registry as PDF. */
    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam(defaultValue = "ALL") RnRegistryView view,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) String municipality,
            @RequestParam(required = false) Long typeId,
            @RequestParam(defaultValue = "false") boolean foreignOnly,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String street,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String lessor) {
        byte[] pdf = exportService.generateRegistryPdf(
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"registar-rb.pdf\"")
                .body(pdf);
    }

    /**
     * Prijavljeni iznajmljivač smije preuzeti samo akte koji se odnose na njegove RB-ove —
     * on im je i adresat. Tuđi RB se prijavljuje kao 404, da endpoint ne otkriva postojanje.
     *
     * <p>Vlasništvo se provjerava po tipu prijave, kao i „moji RB-ovi" liste: username/password
     * flow po {@code lessorId}, NIAS po OIB-u iz SAML-a (lessorId nije stabilan između prijava).
     * Ostale prijave (buduće interne role) prolaze.
     */
    private void requireAccess(String rn, Authentication authentication) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (authentication.getPrincipal() instanceof LessorPrincipal principal) {
            if (!rnRepository.isOwnedByLessor(rn, principal.getLessorId())) {
                throw new ResourceNotFoundException("rn not found: " + rn);
            }
            return;
        }
        Optional<String> oib = NiasOibExtractor.extractOib(authentication);
        if (oib.isPresent() && !rnRepository.isOwnedByOib(rn, oib.get())) {
            throw new ResourceNotFoundException("rn not found: " + rn);
        }
    }
}
