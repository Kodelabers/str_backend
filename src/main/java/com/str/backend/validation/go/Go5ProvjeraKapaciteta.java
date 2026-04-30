package com.str.backend.validation.go;

import com.str.backend.validation.ValidationCheck;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class Go5ProvjeraKapaciteta implements ValidationCheck {

    private static final String STEP = "GO-5";

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 5; }

    @Override
    public ValidationResult check(ValidationContext context) {
        return new ValidationResult.Passed(STEP,
                "kreveti=" + context.accommodation().getMaxBeds()
                        + ", gosti=" + context.accommodation().getMaxGuests());
    }
}
