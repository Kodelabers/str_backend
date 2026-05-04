package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.validation.ValidationCheck;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
public class Go4SuglasnostSuvlasnika implements ValidationCheck {

    private static final String STEP = "GO-4";

    private final Clock clock;

    public Go4SuglasnostSuvlasnika(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 4; }

    @Override
    public java.util.Set<String> dependsOn() { return java.util.Set.of("GO-2"); }

    @Override
    public ValidationResult check(ValidationContext context) {
        if (!context.requiresCoOwnerConsent()) {
            return new ValidationResult.Passed(STEP, "not required (GO-2 did not flag)");
        }
        AccommodationEntity accommodation = context.accommodation();
        Boolean consent = accommodation.getCoOwnerConsent();
        if (consent == null || !consent) {
            return new ValidationResult.Rejected(STEP, "error.go4.consent.missing");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate withdrawalDate = accommodation.getConsentWithdrawalDate();
        if (withdrawalDate != null && !withdrawalDate.isAfter(today)) {
            return new ValidationResult.Rejected(STEP, "error.go4.consent.withdrawn");
        }
        return new ValidationResult.Passed(STEP, "suglasnost valjana");
    }
}
