package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.registries.MpgiClient;
import com.str.backend.validation.ValidationCheck;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class Go2TipGradevine implements ValidationCheck {

    private static final String STEP = "GO-2";
    private static final int UNIT_THRESHOLD = 3;

    private final MpgiClient mpgiClient;

    public Go2TipGradevine(MpgiClient mpgiClient) {
        this.mpgiClient = mpgiClient;
    }

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 1; }

    @Override
    public ValidationResult check(ValidationContext context) {
        AccommodationEntity accommodation = context.accommodation();
        if (!accommodation.isBuilding() || !accommodation.isApartments()) {
            return new ValidationResult.Passed(STEP, "nije zgrada/stanovi - GO-4 nije obvezan");
        }
        String address = accommodation.getStreet() + " " + accommodation.getStreetNumber() + ", " + accommodation.getCity();
        int units = mpgiClient.brojStambenihJedinica(address);
        if (units > UNIT_THRESHOLD) {
            context.markCoOwnerConsentRequired();
            return new ValidationResult.Passed(STEP,
                    "zgrada s " + units + " jedinica - GO-4 obvezan");
        }
        return new ValidationResult.Passed(STEP, units + " jedinica - GO-4 nije obvezan");
    }
}
