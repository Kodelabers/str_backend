package com.str.backend.validation.go;

import com.str.backend.validation.ValidationCheck;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class Go3LegalityCheck implements ValidationCheck {

    private static final String STEP = "GO-3";

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 3; }

    @Override
    public Set<String> dependsOn() { return Set.of("GO-2"); }

    @Override
    public ValidationResult check(ValidationContext context) {
        return new ValidationResult.Passed(STEP, "legalizirano");
    }
}
