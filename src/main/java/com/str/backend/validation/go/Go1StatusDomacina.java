package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.validation.ValidationCheck;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class Go1StatusDomacina implements ValidationCheck {

    private static final String STEP = "GO-1";

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 1; }

    @Override
    public ValidationResult check(ValidationContext context) {
        LessorEntity lessor = context.lessor();
        AccommodationEntity accommodation = context.accommodation();

        boolean countyMatches = equalsIgnoreCaseNullSafe(lessor.getCounty(), accommodation.getCounty());
        boolean isHost = countyMatches && !accommodation.isBuilding();

        accommodation.markHost(isHost);

        return new ValidationResult.Passed(STEP,
                "host=" + isHost + " (county=" + countyMatches + ", building=" + accommodation.isBuilding() + ")");
    }

    private static boolean equalsIgnoreCaseNullSafe(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
