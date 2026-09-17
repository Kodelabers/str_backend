package com.str.backend.exception;

import com.str.backend.captcha.CaptchaException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** PostgreSQL i H2 jednako označavaju prekršenu jedinstvenost. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Odgovor je namjerno štur (stranci se ne objašnjava zašto je provjera pala), pa bez ovog
     * loga „Potrebna je ALTCHA provjera" ne kaže NIŠTA o uzroku. A uzroci su bitno različiti:
     * fronta bez widgeta uopće ne šalje zaglavlje, dok riješena pa istekla captcha šalje puno.
     * Zato se bilježi putanja i je li {@code X-Altcha} uopće stigao — ne i sadržaj.
     */
    @ExceptionHandler(CaptchaException.class)
    public ResponseEntity<ErrorResponse> handleCaptcha(CaptchaException ex, HttpServletRequest request) {
        String header = request.getHeader("X-Altcha");
        log.warn("captcha_odbijena razlog={} putanja={} X-Altcha={}",
                ex.getMessage(),
                request.getRequestURI(),
                header == null ? "NIJE POSLAN (fronta ga ne šalje ili je build stariji od captche)"
                        : header.isBlank() ? "prazan" : "poslan (" + header.length() + " znakova)");
        return build(HttpStatus.UNPROCESSABLE_ENTITY, resolve(ex.getMessage()), null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, resolve(ex.getMessage()), null);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        return build(HttpStatus.BAD_REQUEST, resolve(ex.getMessage()), null);
    }

    /**
     * Datoteka veća od {@code spring.servlet.multipart.max-file-size} (10 MB). Bez ovog rukovatelja
     * Spring vrati 500, pa bi korisnik na uploadu skeniranog rješenja dobio „greška na serveru"
     * umjesto poruke o veličini — a frontend to ograničenje već prikazuje u dropzoneu.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, resolve("error.upload.file.tooLarge"), null);
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(IllegalStatusTransitionException ex) {
        return build(HttpStatus.CONFLICT, resolve("error.status.transition.illegal"), null);
    }

    @ExceptionHandler(ValidationRejectedException.class)
    public ResponseEntity<ErrorResponse> handleValidationRejected(ValidationRejectedException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, resolve(ex.getMessage()),
                Map.of("step", ex.getStep()));
    }

    @ExceptionHandler(DuplicateLocationException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateLocation(DuplicateLocationException ex) {
        return build(HttpStatus.CONFLICT, resolve(ex.getMessage()), Map.of(
                "code", "DUPLICATE_LOCATION",
                "existingRegistrationNumber", ex.getExistingRegistrationNumber()));
    }

    @ExceptionHandler(ExternalRegistryException.class)
    public ResponseEntity<ErrorResponse> handleRegistry(ExternalRegistryException ex) {
        log.error("external_registry_error registry={} message={}", ex.getRegistry(), ex.getMessage(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                resolve("error.external.registry.unavailable"),
                Map.of("registry", ex.getRegistry()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'";
        return build(HttpStatus.BAD_REQUEST, message, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        return build(HttpStatus.BAD_REQUEST, resolve("error.validation.failed"), null);
    }

    // Spring Framework 6.1+ throws HandlerMethodValidationException (not ConstraintViolationException)
    // for @Pattern/@NotBlank/etc. on @PathVariable/@RequestParam in @Validated controllers.
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, resolve("error.validation.failed"), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(err -> fieldErrors.put(err.getField(), resolve(err.getDefaultMessage())));
        return build(HttpStatus.BAD_REQUEST, resolve("error.validation.failed"), fieldErrors);
    }

    /**
     * Prekršen constraint u bazi. Razlikuju se DVA bitno različita slučaja, jer ih je pogrešno
     * svesti na isti status:
     *
     * <ul>
     *   <li><b>Duplikat (SQLState 23505)</b> → {@code 409}. Zahtjev je u sukobu sa zatečenim
     *       podacima; poslužitelj radi ispravno. Poznati slučaj: utrka dviju paralelnih non-EU
     *       samoregistracija na {@code uk_lessor_username}, i sudar pri dodjeli RB-a
     *       ({@code RnService} provjerava jedinstvenost prije upisa, ali paralelni upis može proći).</li>
     *   <li><b>Sve ostalo</b> (NOT NULL 23502, strani ključ 23503, CHECK 23514) → {@code 500}.
     *       To NISU sukobi sa stranom, nego greške u našem kodu ili shemi. Prikazati ih kao 409
     *       „podaci su u sukobu" značilo bi tiho svaliti krivnju na korisnika i sakriti bug —
     *       zato ostaju glasni, s {@code log.error} i punim stack traceom.</li>
     * </ul>
     *
     * <p><b>U log ide samo naziv constrainta, nikad poruka baze.</b> PostgreSQL uz duplikat
     * ispisuje i vrijednost ključa ({@code Key (email)=(...) already exists}), što je osobni
     * podatak — isti razlog zbog kojeg se na SAML stablu ne pali TRACE.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        String constraint = null;
        boolean duplicate = ex instanceof DuplicateKeyException;
        for (Throwable c = ex; c != null; c = c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException hibernateEx) {
                constraint = hibernateEx.getConstraintName();
            }
            if (c instanceof SQLException sql && SQLSTATE_UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                duplicate = true;
            }
        }

        if (!duplicate) {
            // Namjerno log.error sa stack traceom: ovo je kvar, ne korisnička greška.
            log.error("data_integrity_violation constraint={} — nije duplikat, tretira se kao kvar",
                    constraint, ex);
            return build(HttpStatus.INTERNAL_SERVER_ERROR, resolve("error.internal"), null);
        }

        log.warn("data_integrity_duplicate constraint={}", constraint);
        // `code` prati dogovor s frontendom: na 409 se prvo čita details.code (v. DUPLICATE_LOCATION),
        // pa bez njega fronta ne može razlikovati ovaj sukob od ostalih.
        return build(HttpStatus.CONFLICT, resolve("error.data.conflict"),
                Map.of("code", "DATA_CONFLICT"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("unhandled_exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, resolve("error.internal"), null);
    }

    private String resolve(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String message, Object details) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), message, details, Instant.now()));
    }

    @Getter
    @AllArgsConstructor
    public static class ErrorResponse {
        private int status;
        private String message;
        private Object details;
        private Instant timestamp;
    }
}
