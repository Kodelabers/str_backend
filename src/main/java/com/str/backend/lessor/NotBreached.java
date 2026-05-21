package com.str.backend.lessor;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotBreachedValidator.class)
public @interface NotBreached {

    String message() default "{lessor.password.breached}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
