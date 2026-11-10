package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.rn.dto.RnPublicView;
import com.str.backend.rn.dto.VerifyResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * STR-1.4-001: provjera registracijskog broja, ista za javnu provjeru ({@link VerifyController},
 * uz captchu) i za SDEP ({@code /api/sdep/v1/verify}, uz token SDEP-a). ACTIVE, SUSPENSION_PROPOSED i
 * WITHDRAWAL_PROPOSED (T12) vraćaju podatke o objektu prikladne za oglas, uz stvarni status — prijedlog
 * suspenzije ili povlačenja tek pokreće rok za očitovanje, a broj i dalje vrijedi. SUSPENDED vraća samo
 * status; isto vraća i prijedlog povlačenja pokrenut iz suspenzije ({@link RnEntity#publicStatus}), koji
 * javno ostaje suspendiran. REVOKED, WITHDRAWN i nepostojeći broj izgledaju isto, {@code {valid:false}} (čl. 4. st. 5. STR
 * Uredbe).
 */
@Component
public class RnVerification {

    private final RnRepository rnRepository;

    public RnVerification(RnRepository rnRepository) {
        this.rnRepository = rnRepository;
    }

    @Transactional(readOnly = true)
    public VerifyResponse verify(String rn) {
        RnEntity entity = rnRepository.findById(rn).orElse(null);
        // Javni status: prijedlog povlačenja iz suspenzije je i dalje SUSPENDED (T12).
        RnStatus status = entity == null ? null : entity.publicStatus();
        if (entity != null && entity.isValidForPublicUse()) {
            return rnRepository.findPublicView(rn)
                    .map(v -> toActive(status, v))
                    .orElseGet(VerifyResponse::invalid);
        }
        if (status == RnStatus.SUSPENDED) {
            return VerifyResponse.suspended();
        }
        // REVOKED, WITHDRAWN or not found — all indistinguishable to the public.
        return VerifyResponse.invalid();
    }

    private static VerifyResponse toActive(RnStatus status, RnPublicView v) {
        return VerifyResponse.active(status, v.rn(), v.accommodationName(), v.category(),
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
