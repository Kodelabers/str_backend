package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.core.CoreObjektEntity;
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
        AccommodationEntity accommodation = context.accommodation();
        CoreObjektEntity core = context.coreObject();
        if (core == null) {
            return new ValidationResult.Passed(STEP,
                    "nema core rjesenja - prihvacamo prijavljeni kapacitet");
        }
        if (accommodation.getMaxBeds() > core.getMaxKreveta()) {
            return new ValidationResult.Rejected(STEP,
                    "max_kreveta=" + accommodation.getMaxBeds() + " prelazi rjesenje=" + core.getMaxKreveta());
        }
        if (accommodation.getMaxGuests() > core.getMaxGostiju()) {
            return new ValidationResult.Rejected(STEP,
                    "max_gostiju=" + accommodation.getMaxGuests() + " prelazi rjesenje=" + core.getMaxGostiju());
        }
        return new ValidationResult.Passed(STEP,
                "kreveti=" + accommodation.getMaxBeds() + "/" + core.getMaxKreveta()
                        + ", gosti=" + accommodation.getMaxGuests() + "/" + core.getMaxGostiju());
    }
}
