package com.str.backend.auth.nias;

import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.rn.RnRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/nias")
public class NiasController {

    private final NiasOibResolver oibResolver;
    private final RnRepository rnRepository;

    public NiasController(NiasOibResolver oibResolver, RnRepository rnRepository) {
        this.oibResolver = oibResolver;
        this.rnRepository = rnRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<NiasMeResponse> me(Authentication authentication) {
        return oibResolver.resolve(authentication)
                .map(oib -> ResponseEntity.ok(new NiasMeResponse(oib)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /**
     * Lista RB-ova za prijavljenog NIAS korisnika, identificiranog po OIB-u iz SAML
     * principala. Na local/mock profilu se fallback-a na konfigurirani mock OIB
     * (vidi {@link NiasOibResolver}); seedani su 3 RB-a u changesetu 048. Na dev/cdu
     * bez prave NIAS sesije vraća 401, a kad je sesija aktivna vraća praznu listu
     * dok stvarni podaci ne postanu dostupni.
     */
    @GetMapping("/registrations")
    public List<LessorRnSummaryDto> registrations(Authentication authentication) {
        String oib = oibResolver.resolve(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return rnRepository.findByLessorOib(oib);
    }
}
