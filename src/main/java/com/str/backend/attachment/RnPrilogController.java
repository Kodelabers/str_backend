package com.str.backend.attachment;

import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.common.VrstaDatoteke;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.rn.RnAccessGuard;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Prilozi na back-office putu: učitavanje službenika (staging, uz prijedlog suspenzije, suspenziju i
 * povlačenje) i preuzimanje priloga RB-a.
 *
 * <p>{@code /api/rn/attachments} je pod {@code /api/rn/**} i traži rolu {@code RN_MANAGEMENT}
 * ({@code SecurityConfig}). {@code /api/rn/{rn}/attachments/**} je kao {@code /documents/**}: prijava
 * ili rola, a vlasništvo odnosno opseg službenika provjerava {@link RnAccessGuard}.
 */
@Validated
@RestController
@RequestMapping("/api/rn")
public class RnPrilogController {

    /** Preglednik ih prikazuje sam; Word se preuzima. */
    private static final Set<String> INLINE = Set.of(VrstaDatoteke.PDF.contentType(),
            VrstaDatoteke.JPEG.contentType(), VrstaDatoteke.PNG.contentType());

    private final PrilogService service;
    private final InternalAccessService internalAccess;
    private final RnAccessGuard accessGuard;

    public RnPrilogController(PrilogService service, InternalAccessService internalAccess,
                              RnAccessGuard accessGuard) {
        this.service = service;
        this.internalAccess = internalAccess;
        this.accessGuard = accessGuard;
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PrilogResponse upload(@RequestParam(value = "datoteka", required = false) MultipartFile datoteka,
                                 Authentication authentication) {
        return service.stage(ucitao(authentication), datoteka);
    }

    @DeleteMapping("/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        service.deleteStaged(ucitao(authentication), id);
    }

    /**
     * Prilog vezan uz pismeno RB-a — vlasniku i službeniku u opsegu, kao i akti. PDF i slike se
     * otvaraju u pregledniku, Word se preuzima; {@link ContentDisposition} RFC 5987-enkodira naziv.
     */
    @GetMapping("/{rn}/attachments/{id}")
    public ResponseEntity<byte[]> download(
            @PathVariable @Pattern(regexp = RegistrationNumber.REGEXP) String rn,
            @PathVariable Long id,
            Authentication authentication) {
        accessGuard.requireAccess(rn, authentication);
        PrilogSadrzaj prilog = service.zaPreuzimanje(rn, id);
        ContentDisposition.Builder builder = INLINE.contains(prilog.contentType())
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        builder.filename(prilog.naziv(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(prilog.contentType()))
                .body(prilog.sadrzaj());
    }

    private String ucitao(Authentication authentication) {
        return internalAccess.actorFor(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }
}
