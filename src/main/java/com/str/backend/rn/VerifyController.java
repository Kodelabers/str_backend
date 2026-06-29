package com.str.backend.rn;

import com.str.backend.domain.RegistrationNumber;
import com.str.backend.domain.RnStatus;
import com.str.backend.rn.dto.RnPublicView;
import com.str.backend.rn.dto.VerifyResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;
import java.util.stream.Stream;

@Validated
@RestController
@RequestMapping("/api/verify")
public class VerifyController {

    private final RnRepository rnRepository;

    public VerifyController(RnRepository rnRepository) {
        this.rnRepository = rnRepository;
    }

    /**
     * STR-1.4-001: public verification of a registration number. ACTIVE returns advertise-safe
     * object data; SUSPENDED returns the status only; WITHDRAWN and unknown RNs are reported
     * identically as {@code {valid:false}} (privacy — čl. 4. st. 5. STR Uredbe).
     */
    @GetMapping("/{rn}")
    @Transactional(readOnly = true)
    public VerifyResponse verify(
            @PathVariable @Pattern(regexp = RegistrationNumber.REGEXP) String rn) {
        RnStatus status = rnRepository.findById(rn).map(RnEntity::getStatus).orElse(null);
        if (status == RnStatus.ACTIVE) {
            return rnRepository.findPublicView(rn)
                    .map(VerifyController::toActive)
                    .orElseGet(VerifyResponse::invalid);
        }
        if (status == RnStatus.SUSPENDED) {
            return VerifyResponse.suspended();
        }
        // WITHDRAWN, IN_PROCESSING, or not found — all indistinguishable to the public.
        return VerifyResponse.invalid();
    }

    private static VerifyResponse toActive(RnPublicView v) {
        return VerifyResponse.active(v.rn(), v.accommodationName(), v.category(),
                composeAddress(v.street(), v.streetNumber(), v.city()), v.group(), v.type());
    }

    /** "Ulica 12, Grad" — skips blank parts and joins street + number, then city. */
    private static String composeAddress(String street, String streetNumber, String city) {
        String streetLine = Stream.of(street, streetNumber)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
        String address = Stream.of(streetLine, city)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
        return address.isBlank() ? null : address;
    }
}
