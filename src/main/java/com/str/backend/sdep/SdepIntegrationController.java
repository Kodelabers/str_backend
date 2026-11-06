package com.str.backend.sdep;

import com.str.backend.activity.AccommodationActivityService;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.GlobalExceptionHandler;
import com.str.backend.lookup.OnlinePlatformEntity;
import com.str.backend.lookup.OnlinePlatformRepository;
import com.str.backend.rn.RnMapper;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.rn.RnVerification;
import com.str.backend.rn.dto.RnAccommodationStatsDto;
import com.str.backend.rn.dto.RnResponse;
import com.str.backend.rn.dto.VerifyResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Integracijski API za SDEP (T1, T2, T4). Zaštita je token SDEP-a ({@link SdepSecurityConfig}),
 * a tijela zahtjeva i odgovora ista su kao na postojećim putanjama za službenike i javnu provjeru:
 *
 * <ul>
 *   <li>{@code GET /verify/{rn}} — kao {@code /api/verify/{rn}}, bez captche;</li>
 *   <li>{@code POST /activities} — kao {@code /api/activity/ingest}, ali platforma po nazivu;</li>
 *   <li>{@code POST /rn/{rn}/suspend} — kao {@code /api/rn/{rn}/suspend}, akter {@value #ACTOR};</li>
 *   <li>{@code POST /accommodation-stats} — JLS i broj kreveta po RB-u, za DZS.</li>
 * </ul>
 *
 * <p>{@code jti} tokena je {@code X-Request-ID} poziva u SDEP-u, pa se u logu zapisuje uz svaki poziv.
 *
 * <p>Neispravan unos vraća 400 s poljem i razlogom ({@code details: {"entries[3].periodFrom": "..."}}),
 * kako je dogovoreno sa SDEP-om (T7). Zato je kontroler bez {@code @Validated} (uz njega Spring
 * tijelo provjerava kao parametar metode, bez popisa polja) i ima vlastiti handler: uz
 * {@code spring.mvc.problemdetails.enabled=true} grešku bi inače preuzeo Springov ProblemDetail
 * handler, a ne {@code GlobalExceptionHandler#handleValidation}.
 */
@RestController
@RequestMapping("/api/sdep/v1")
public class SdepIntegrationController {

    /** Akter u {@code registration_number_log} za radnje koje pokrene SDEP; akt potpisuje tijelo. */
    static final String ACTOR = "SDEP";

    /** SDEP traži podatke za jednu stranicu aktivnosti, a stranica ima najviše 1000 redaka. */
    static final int MAX_STATS_RNS = 1000;

    private static final Pattern RN_FORMAT = Pattern.compile(RegistrationNumber.REGEXP);

    private static final Logger log = LoggerFactory.getLogger(SdepIntegrationController.class);

    private final RnVerification verification;
    private final AccommodationActivityService activities;
    private final OnlinePlatformRepository platforms;
    private final RnService rnService;
    private final RnMapper rnMapper;
    private final RnRepository rnRepository;
    private final MessageSource messages;

    public SdepIntegrationController(RnVerification verification, AccommodationActivityService activities,
                                     OnlinePlatformRepository platforms, RnService rnService, RnMapper rnMapper,
                                     RnRepository rnRepository, MessageSource messages) {
        this.verification = verification;
        this.activities = activities;
        this.platforms = platforms;
        this.rnService = rnService;
        this.rnMapper = rnMapper;
        this.rnRepository = rnRepository;
        this.messages = messages;
    }

    /** T2: provjera broja za platformu preko SDEP-a, uživo i bez captche. */
    @GetMapping("/verify/{rn}")
    public VerifyResponse verify(@PathVariable String rn) {
        if (!RN_FORMAT.matcher(rn).matches()) {
            throw new BusinessException("error.validation.failed");
        }
        return verification.verify(rn);
    }

    /**
     * STR-3.1: predaja aktivnosti, uz ispravke i otkazivanja (T6). Odgovor je raščlamba
     * {@code {received, inserted, updated, unchanged}}. Platforma se traži po nazivu iz SDEP-a, bez
     * razlike velikih i malih slova. Nepoznata platforma je 400: nova se zasad ne dodaje sama
     * (T3, čeka dogovor o registraciji platformi).
     */
    @PostMapping("/activities")
    public ResponseEntity<AccommodationActivityService.IngestResult> activities(
            @Valid @RequestBody SdepActivitiesRequest req, @AuthenticationPrincipal Jwt jwt) {
        OnlinePlatformEntity platform = platforms.findFirstByActiveTrueAndNameIgnoreCase(req.getPlatformName().strip())
                .orElseThrow(() -> {
                    log.warn("sdep_ingest_unknown_platform zahtjev={} platforma='{}'", jwt.getId(), req.getPlatformName());
                    return new BusinessException("error.sdep.platform.unknown");
                });
        log.info("sdep_ingest_request zahtjev={} platforma='{}' count={}", jwt.getId(), platform.getName(),
                req.getEntries().size());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(activities.ingest(platform.getPlatformId(), req.getEntries()));
    }

    /**
     * Prijedlog suspenzije nakon odluke službenika o spornom oglasu. Pravila su ista kao za
     * službenika u STR-u ({@link RnService#suspend}): rok za očitovanje je obavezan i ne smije biti u
     * prošlosti, a za {@code OTHER} treba obrazloženje.
     */
    @PostMapping("/rn/{rn}/suspend")
    public RnResponse suspend(
            @PathVariable String rn,
            @RequestParam RnTrigger reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate suspensionDeadline,
            @RequestParam(required = false) String note,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("sdep_suspend_request zahtjev={} rn={} razlog={} rok={}", jwt.getId(), rn, reason, suspensionDeadline);
        return rnMapper.toResponse(rnService.suspend(rn, reason, suspensionDeadline, note, ACTOR, List.of()));
    }

    /** 400 s popisom neispravnih polja, u obliku kao {@code GlobalExceptionHandler}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<GlobalExceptionHandler.ErrorResponse> invalidRequest(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(err -> fields.putIfAbsent(err.getField(), err.getDefaultMessage()));
        log.warn("sdep_request_invalid polja={}", fields);
        String message = messages.getMessage("error.validation.failed", null, "error.validation.failed",
                LocaleContextHolder.getLocale());
        return ResponseEntity.badRequest().body(new GlobalExceptionHandler.ErrorResponse(
                HttpStatus.BAD_REQUEST.value(), message, fields, Instant.now()));
    }

    /** T4: JLS i najveći broj kreveta po RB-u, za mjesečnu dostavu DZS-u. Nepoznat RB nije u odgovoru. */
    @PostMapping("/accommodation-stats")
    public List<RnAccommodationStatsDto> accommodationStats(@RequestBody List<String> rns) {
        if (rns.isEmpty()) {
            return List.of();
        }
        if (rns.size() > MAX_STATS_RNS) {
            throw new BusinessException("error.sdep.stats.tooMany");
        }
        return rnRepository.findAccommodationStats(new LinkedHashSet<>(rns));
    }
}
