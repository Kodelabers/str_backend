package com.str.backend.lessor;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LessorRegistrationRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void noLegalEntityGroup_isValid() {
        LessorRegistrationRequest req = validRequest();

        assertThat(violationKeys(req)).doesNotContain("legalEntityGroupComplete");
    }

    @Test
    void legalEntityFlagTrueWithAllFields_isValid() {
        LessorRegistrationRequest req = validRequest();
        req.setVlasnikJePravnaOsoba(true);
        req.setNazivPravneOsobe("Acme Holdings Ltd");
        req.setDrzavaSjedistaId(380);
        req.setGradSjedista("Milano");
        req.setMaticniBrojPravneOsobe("MI-12345678");

        assertThat(violationKeys(req)).doesNotContain("legalEntityGroupComplete");
    }

    @Test
    void legalEntityFlagTrueWithBlankField_reportsViolation() {
        LessorRegistrationRequest req = validRequest();
        req.setVlasnikJePravnaOsoba(true);
        req.setNazivPravneOsobe("Acme Holdings Ltd");
        req.setDrzavaSjedistaId(380);
        req.setGradSjedista("   ");          // blank
        req.setMaticniBrojPravneOsobe("MI-12345678");

        Set<ConstraintViolation<LessorRegistrationRequest>> violations = validator.validate(req);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("legalEntityGroupComplete")
                        && v.getMessage().equals("lessor.legalEntity.incomplete"));
    }

    @Test
    void legalEntityFlagTrueWithNullCountry_reportsViolation() {
        LessorRegistrationRequest req = validRequest();
        req.setVlasnikJePravnaOsoba(true);
        req.setNazivPravneOsobe("Acme Holdings Ltd");
        req.setDrzavaSjedistaId(null);       // missing country
        req.setGradSjedista("Milano");
        req.setMaticniBrojPravneOsobe("MI-12345678");

        Set<ConstraintViolation<LessorRegistrationRequest>> violations = validator.validate(req);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("legalEntityGroupComplete")
                        && v.getMessage().equals("lessor.legalEntity.incomplete"));
    }

    private static Set<String> violationKeys(LessorRegistrationRequest req) {
        return validator.validate(req).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static LessorRegistrationRequest validRequest() {
        LessorRegistrationRequest req = new LessorRegistrationRequest();
        req.setIme("John");
        req.setPrezime("Doe");
        req.setDatumRodjenja(LocalDate.of(1985, 6, 15));
        req.setPorezniBroj("12345678");
        req.setZemljaPrebivalistaId(1);
        req.setStalnaAdresa("123 Main St, Springfield");
        req.setVrstaIsprave("PASSPORT");
        req.setBrojIsprave("AB123456");
        req.setEmail("john@example.com");
        req.setPassword("StrongPassw0rd!");
        req.setPasswordPotvrda("StrongPassw0rd!");
        req.setIspravaPrednja(
                new MockMultipartFile("ispravaPrednja", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}));
        return req;
    }
}
