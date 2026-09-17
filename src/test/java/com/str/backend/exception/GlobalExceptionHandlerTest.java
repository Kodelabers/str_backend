package com.str.backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prekršen constraint nije jedna vrsta događaja: duplikat je sukob sa zatečenim podacima (409),
 * a NOT NULL / strani ključ su kvar u našem kodu (500). Test pin-a tu razliku jer je lako
 * izgubiti — dovoljno je vratiti se na „sve na 409" i svaki bug u shemi tiho postaje korisnička
 * greška.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messageSource());

    private static StaticMessageSource messageSource() {
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("error.data.conflict", Locale.getDefault(), "Sukob podataka");
        source.addMessage("error.internal", Locale.getDefault(), "Interna greška");
        return source;
    }

    /** SQLState 23505 = prekršena jedinstvenost. */
    private static DataIntegrityViolationException duplicate(String constraintName) {
        return new DataIntegrityViolationException("wrapper",
                new org.hibernate.exception.ConstraintViolationException(
                        "could not execute statement",
                        new SQLException("duplicate key value violates unique constraint", "23505"),
                        constraintName));
    }

    @Test
    void duplicateKey_mapsTo409_withCodeForFrontend() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleDataIntegrity(duplicate("uq_lessor_email"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Sukob podataka");
        // Fronta na 409 grana po details.code (vidi DUPLICATE_LOCATION u RegistrationNumberPage).
        assertThat(response.getBody().getDetails()).isEqualTo(Map.of("code", "DATA_CONFLICT"));
    }

    @Test
    void notNullViolation_mapsTo500_notAUserConflict() {
        DataIntegrityViolationException notNull = new DataIntegrityViolationException("wrapper",
                new org.hibernate.exception.ConstraintViolationException(
                        "could not execute statement",
                        new SQLException("null value in column violates not-null constraint", "23502"),
                        "nn_lessor_email"));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleDataIntegrity(notNull);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Interna greška");
        assertThat(response.getBody().getDetails()).isNull();
    }

    @Test
    void foreignKeyViolation_mapsTo500() {
        DataIntegrityViolationException fk = new DataIntegrityViolationException("wrapper",
                new org.hibernate.exception.ConstraintViolationException(
                        "could not execute statement",
                        new SQLException("violates foreign key constraint", "23503"),
                        "fk_submission_lessor"));

        assertThat(handler.handleDataIntegrity(fk).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** Bez SQLException u lancu se ne smije nagađati da je duplikat. */
    @Test
    void unknownCause_mapsTo500() {
        DataIntegrityViolationException opaque =
                new DataIntegrityViolationException("nesto je puklo", new IllegalStateException("bez SQLState-a"));

        assertThat(handler.handleDataIntegrity(opaque).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
