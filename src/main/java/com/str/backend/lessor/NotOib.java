package com.str.backend.lessor;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Vrijednost ne smije biti valjan OIB. Samoregistracija je za strance bez OIB-a — tko ima OIB,
 * prijavljuje se preko NIAS-a (e-Građani / eIDAS). Ulazni ekran na frontendu samo usmjerava;
 * ovo je provjera koja se ne može zaobići.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotOibValidator.class)
public @interface NotOib {

    String message() default "{lessor.oib.notAllowed}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
