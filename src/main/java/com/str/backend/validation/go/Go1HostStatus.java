package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.validation.ValidationCheck;
import com.str.backend.validation.ValidationContext;
import com.str.backend.validation.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class Go1HostStatus implements ValidationCheck {

    private static final String STEP = "GO-1";

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 1; }

    @Override
    public ValidationResult check(ValidationContext context) {
        LessorEntity lessor = context.lessor();
        AccommodationEntity accommodation = context.accommodation();

        // Pravna osoba bez poznate adrese sjedišta (e-Zastupanja — e-Ovlaštenja je ne daju): županije
        // nema s čim usporediti. Ishod je isti kao usporedba ispod (prazna županija se nikad ne
        // poklapa), samo označen kao „ne utvrđuje se", da ne izgleda kao pala provjera. Je li pravna
        // osoba ikad domaćin, čeka odluku naručitelja.
        if (lessor.isLegalEntityOwner() && (lessor.getCounty() == null || lessor.getCounty().isBlank())) {
            accommodation.markHost(false);
            return new ValidationResult.Passed(STEP,
                    "host=false (pravna osoba: status domaćina se ne utvrđuje — adresa sjedišta nije poznata)");
        }

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
