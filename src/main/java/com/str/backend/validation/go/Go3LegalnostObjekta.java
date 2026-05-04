package com.str.backend.validation.go;

import com.str.backend.validation.ValidationCheck;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class Go3LegalnostObjekta implements ValidationCheck {

    private static final String STEP = "GO-3";

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 3; }

    @Override
    public ValidationResult check(ValidationContext context) {
        if (!context.accommodation().isLegalized()) {
            return new ValidationResult.Rejected(STEP, "error.go3.not.legalized");
        }
        return new ValidationResult.Passed(STEP, "legalizirano");
    }
}
