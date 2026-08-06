package com.str.backend.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
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
