package com.str.backend.rn;

import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.WithdrawalReason;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.dto.RnDetailResponse;
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
import org.springframework.http.MediaType;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/rn")
public class RnController {

    private final RnService service;
    private final RnMapper mapper;
    private final StrDocumentService documentService;
    private final RnDocumentsService documentsService;
    private final StatisticsExportService exportService;
    private final InternalAccessService internalAccess;
    private final RnRegistrySearch registrySearch;
    private final CountyScopeService countyScope;
    private final RnScopeGuard scopeGuard;
    private final RnAccessGuard accessGuard;
    private final RucniAktService rucniAkt;

    public RnController(RnService service, RnMapper mapper, StrDocumentService documentService,
                        RnDocumentsService documentsService, StatisticsExportService exportService,
                        InternalAccessService internalAccess, RnRegistrySearch registrySearch,
                        CountyScopeService countyScope, RnScopeGuard scopeGuard, RnAccessGuard accessGuard,
                        RucniAktService rucniAkt) {
        this.rucniAkt = rucniAkt;
        this.service = service;
        this.countyScope = countyScope;
        this.scopeGuard = scopeGuard;
        this.registrySearch = registrySearch;
        this.mapper = mapper;
        this.documentService = documentService;
        this.documentsService = documentsService;
        this.exportService = exportService;
        this.internalAccess = internalAccess;
        this.accessGuard = accessGuard;
    }

    /** STR-1.5: display of inactive RNs (SUSPENDED + REVOKED + WITHDRAWN), u opsegu službenika. */
    @GetMapping("/inactive")
    public List<RnResponse> inactive(Authentication authentication) {
        return mapper.toResponseList(service.inactive(countyScope.accommodationCounties(authentication)));
    }

    /**
     * STR wireframe §12 / §13: paginirani registar RB-ova za službenike (rola RN_MANAGEMENT, v.
     * {@code SecurityConfig}) — javnosti je dostupna samo provjera pojedinog broja ({@code /api/verify}).
     *
     * <p>{@code deadlineWithinDays} je radna lista tražena na UAT-u: RB-ovi kojima rok za
     * očitovanje ističe u narednih x dana. Namjerno je filtar ovdje, a ne zaseban pregled —
     * ovaj endpoint već nosi ostale filtre, paging, sortiranje i tri izvoza. Uobičajena
     * kombinacija je {@code ?view=SUSPENSION_PROPOSED&deadlineWithinDays=7}.
     *
     * <p>{@code decision} i {@code verified} filtriraju stupce „Rješenje o kategorizaciji" i
     * „Verificiran" (isto pravilo kao „Moji registracijski brojevi"). Filtar „Verificiran" pita
     * eTurizam; kad on nije dostupan, odgovor je 503 ({@link RnRegistrySearch}).
     *
     * <p>Županijski službenik vidi samo svoju županiju ({@link CountyScopeService}); njegov
     * {@code county} iz zahtjeva se zanemaruje, i u izvozima.
     */
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
            @RequestParam(required = false) Integer deadlineWithinDays,
            @RequestParam(required = false) RegistryDecisionFilter decision,
            @RequestParam(required = false) Boolean verified,
            @PageableDefault(size = 20, sort = "issueDate", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication) {
        return registrySearch.search(filter(authentication, view, q, county, municipality, typeId,
                foreignOnly, rb, city, street, name, lessor, deadlineWithinDays, decision, verified),
                pageable);
    }

    /**
     * STR wireframe §12 / §13: full detail of a single RN (accommodation + lessor). Službenik s
     * rolom vidi svaki RB, iznajmljivač samo svoj (tuđi → 404), kao i kod akata. Uz to nosi
     * ponudu, namjenu te kontakt i adresu (prebivalište ili sjedište) iznajmljivača.
     */
    @GetMapping("/{rn}/detail")
    public RnDetailResponse detail(@PathVariable String rn, Authentication authentication) {
        requireAccess(rn, authentication);
        return service.detailView(rn);
    }

    @GetMapping("/{rn}")
    public RnResponse get(@PathVariable String rn, Authentication authentication) {
        requireInScope(rn, authentication);
        return mapper.toResponse(service.find(rn));
    }

    /*
     * Životni ciklus RB-a po službenoj dužnosti — samo uz rolu RN_MANAGEMENT (SecurityConfig).
     * Akter je službenik iz sesije (INTERNAL:<oib>) i ide u registration_number_log. RB izvan
     * opsega službenika (druga županija) je 404, kao nepostojeći.
     */

    /*
     * Rok za očitovanje (suspend, propose-withdrawal) i razlog prijedloga povlačenja su obvezni, ali
     * ih provjerava servis, ne `required = true`: nedostajući parametar bi završio u generičkom
     * handleru kao 500, a servis vraća 400 s prevedenom porukom (error.rn.suspend.deadline.required,
     * error.rn.withdraw.deadline.required, error.rn.withdraw.reason.required).
     *
     * T16: županijski službenik uz svaku radnju šalje i {@code klasa} i {@code urbroj} (ručni akt bez eGOP-a,
     * {@link RucniAktService}); službeniku ministarstva se zanemaruju.
     *
     * Prilozi službenika (T17) idu kao ponovljeni query parametar `prilogIds=1&prilogIds=2` — id-evi
     * iz `POST /api/rn/attachments`, uz isti oblik zahtjeva kao ostali parametri (tijela nema), pa
     * poziv bez priloga ostaje kakav je bio. Najviše 5 priloga i zajedno do
     * `app.attachments.official-max-total` (15 MB, dostava e-poštom non-EU iznajmljivaču).
     */

    @PostMapping("/{rn}/suspend")
    public RnResponse suspend(
            @PathVariable String rn,
            @RequestParam RnTrigger reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate suspensionDeadline,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) List<Long> prilogIds,
            @RequestParam(required = false) String klasa,
            @RequestParam(required = false) String urbroj,
            Authentication authentication) {
        requireInScope(rn, authentication);
        RucniAkt rucni = rucniAkt.zaRadnju(authentication, klasa, urbroj);
        return mapper.toResponse(service.suspend(rn, reason, suspensionDeadline, note, actor(authentication),
                prilogIds != null ? prilogIds : List.of(), rucni));
    }

    @PostMapping("/{rn}/revoke-proposal")
    public RnResponse revokeProposal(@PathVariable String rn,
                                     @RequestParam(required = false) String klasa,
                                     @RequestParam(required = false) String urbroj,
                                     Authentication authentication) {
        requireInScope(rn, authentication);
        RucniAkt rucni = rucniAkt.zaRadnju(authentication, klasa, urbroj);
        return mapper.toResponse(service.revokeProposal(rn, actor(authentication), rucni));
    }

    @PostMapping("/{rn}/reactivate")
    public RnResponse reactivate(@PathVariable String rn,
                                 @RequestParam(required = false) String klasa,
                                 @RequestParam(required = false) String urbroj,
                                 Authentication authentication) {
        requireInScope(rn, authentication);
        RucniAkt rucni = rucniAkt.zaRadnju(authentication, klasa, urbroj);
        return mapper.toResponse(service.reactivate(rn, actor(authentication), rucni));
    }

    /**
     * T12: prijedlog povlačenja s rokom za očitovanje (iz ACTIVE, SUSPENSION_PROPOSED i SUSPENDED).
     * Razlog i rok provjerava servis (400 s prevedenom porukom), kao kod {@link #suspend}. Ručne rute
     * za povlačenje nema, kao ni za suspenziju: broj se povlači istekom roka ({@code WithdrawalDeadlineJob})
     * ili kad službenik nakon roka ne uvaži očitovanje.
     */
    @PostMapping("/{rn}/propose-withdrawal")
    public RnResponse proposeWithdrawal(
            @PathVariable String rn,
            @RequestParam(required = false) WithdrawalReason reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate withdrawalDeadline,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) List<Long> prilogIds,
            @RequestParam(required = false) String klasa,
            @RequestParam(required = false) String urbroj,
            Authentication authentication) {
        requireInScope(rn, authentication);
        RucniAkt rucni = rucniAkt.zaRadnju(authentication, klasa, urbroj);
        return mapper.toResponse(service.proposeWithdrawal(rn, reason, withdrawalDeadline, note,
                actor(authentication), prilogIds != null ? prilogIds : List.of(), rucni));
    }

    /** T12: obustava postupka povlačenja — broj se uvijek vraća u ACTIVE. */
    @PostMapping("/{rn}/revoke-withdrawal-proposal")
    public RnResponse revokeWithdrawalProposal(@PathVariable String rn,
                                               @RequestParam(required = false) String klasa,
                                               @RequestParam(required = false) String urbroj,
                                               Authentication authentication) {
        requireInScope(rn, authentication);
        RucniAkt rucni = rucniAkt.zaRadnju(authentication, klasa, urbroj);
        return mapper.toResponse(service.revokeWithdrawalProposal(rn, actor(authentication), rucni));
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
     * ograničava na vlastite registracijske brojeve. Službenik s rolom
     * {@link InternalAccessService#RN_MANAGEMENT} vidi akte svakog RB-a.
     *
     * <p>Vlasnik dobiva samo zahtjev i dodjelu; akte životnog ciklusa preuzima pohranjene
     * ({@code /documents/pohranjeno/{id}}). Render na zahtjev bilo kojeg akta i slobodan
     * {@code reason} su samo za službenika — inače bi vlasnik aktivnog RB-a mogao dobiti
     * „Obavijest o povlačenju" sa zaglavljem tijela i razlogom po želji, za postupak koji se
     * nikad nije dogodio. Nedopušten tip vraća 404, kao i tuđi RB.
     *
     * <p>{@code Content-Disposition: inline} — dokument se otvara u novom tabu, ne preuzima.
     * Frontend ga mora otvoriti kao <b>top-level navigaciju</b> ({@code window.open}): tako
     * sesijski kolačić putuje pod {@code SameSite=Lax} i {@link #requireAccess} prolazi, dok bi
     * {@code fetch} bez {@code credentials} ili {@code <a download>} dobio 401. {@code <iframe>}
     * ne radi — vrijedi Springov default {@code X-Frame-Options: DENY}.
     *
     * <p>{@code tip=zahtjev} ostaje dostupan i kad je {@code str.rn.documents.zahtjev-visible}
     * ugašen — ta zastavica skriva podnesak <b>samo iz popisa</b>. Podnesak nosi urudžbeni broj 1
     * i po ZUP-u je dio spisa, pa ga se ne smije zatvoriti s 404.
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
        boolean sluzbenik = requireAccess(rn, authentication) == RnAccessGuard.Pristup.SLUZBENIK
                || accessGuard.isOfficial(authentication);
        if (!sluzbenik && !VLASNIKOVI_AKTI.contains(type)) {
            throw new ResourceNotFoundException("document not available: " + type.slug() + " " + rn);
        }
        String razlog = sluzbenik ? reason : null;
        byte[] pdf = switch (type) {
            // Zahtjev nema predložak — servira se pohranjeni podnesak sa submissiona.
            case ZAHTJEV -> documentsService.zahtjevPdf(rn);
            // Pečaćena dodjela je original (ista kao u eGOP-u i KP-u); bez nje se renderira s pravim
            // URBROJ-em izlaznog pismena.
            case DODJELA -> documentsService.sealedDodjela(rn).orElseGet(() ->
                    documentService.render(type, rn, razlog, documentsService.dodjelaFiling(rn)));
            default -> documentService.render(type, rn, razlog);
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + type.slug() + "-" + rn + ".pdf\"")
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
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + doc.filename() + "\"")
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
            @RequestParam(required = false) String lessor,
            @RequestParam(required = false) Integer deadlineWithinDays,
            @RequestParam(required = false) RegistryDecisionFilter decision,
            @RequestParam(required = false) Boolean verified,
            Authentication authentication) {
        byte[] xlsx = exportService.generateRegistryXlsx(filter(authentication,
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor,
                deadlineWithinDays, decision, verified));
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
            @RequestParam(required = false) String lessor,
            @RequestParam(required = false) Integer deadlineWithinDays,
            @RequestParam(required = false) RegistryDecisionFilter decision,
            @RequestParam(required = false) Boolean verified,
            Authentication authentication) {
        byte[] csv = exportService.generateRegistryCsv(filter(authentication,
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor,
                deadlineWithinDays, decision, verified));
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
            @RequestParam(required = false) String lessor,
            @RequestParam(required = false) Integer deadlineWithinDays,
            @RequestParam(required = false) RegistryDecisionFilter decision,
            @RequestParam(required = false) Boolean verified,
            Authentication authentication) {
        byte[] pdf = exportService.generateRegistryPdf(filter(authentication,
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor,
                deadlineWithinDays, decision, verified));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"registar-rb.pdf\"")
                .body(pdf);
    }

    /** Akti koje vlasnik smije renderirati na zahtjev; ostale dobiva samo kao pohranjene. */
    private static final Set<StrDocumentType> VLASNIKOVI_AKTI =
            EnumSet.of(StrDocumentType.ZAHTJEV, StrDocumentType.DODJELA);

    /** Vlasnik ili službenik u opsegu — pravilo je u {@link RnAccessGuard}, zajedničkom s prilozima. */
    private RnAccessGuard.Pristup requireAccess(String rn, Authentication authentication) {
        return accessGuard.requireAccess(rn, authentication);
    }

    /** RB iz županije izvan opsega službenika je 404 ({@link RnScopeGuard}). */
    private void requireInScope(String rn, Authentication authentication) {
        scopeGuard.requireInScope(rn, authentication);
    }

    /**
     * Filtri iz zahtjeva uz opseg službenika. Županija ide kao popis zapisanih oblika naziva
     * ({@link CountyScopeService#accommodationCounties(Authentication, String)}): ograničen opseg
     * zamjenjuje {@code county} iz zahtjeva, a bez njega tražena županija pogađa sve oblike naziva.
     */
    private RnRegistryFilter filter(Authentication authentication, RnRegistryView view, String q, String county,
                                    String municipality, Long typeId, boolean foreignOnly, String rb, String city,
                                    String street, String name, String lessor, Integer deadlineWithinDays,
                                    RegistryDecisionFilter decision, Boolean verified) {
        List<String> counties = countyScope.accommodationCounties(authentication, county);
        return new RnRegistryFilter(view, q, null, municipality, typeId,
                foreignOnly, rb, city, street, name, lessor, deadlineWithinDays, decision, verified, counties);
    }

    /** Endpoint je iza role, pa službenik iz sesije postoji; {@code null} samo u testovima bez lanca. */
    private String actor(Authentication authentication) {
        return internalAccess.actorFor(authentication).orElse(null);
    }
}
