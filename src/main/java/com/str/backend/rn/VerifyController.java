package com.str.backend.rn;

import com.str.backend.captcha.AltchaService;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.rn.dto.VerifyResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/verify")
public class VerifyController {

    private final RnVerification verification;
    private final AltchaService altchaService;

    public VerifyController(RnVerification verification, AltchaService altchaService) {
        this.verification = verification;
        this.altchaService = altchaService;
    }

    /** STR-1.4-001: javna provjera registracijskog broja, uz captchu (pravila u {@link RnVerification}). */
    @GetMapping("/{rn}")
    public VerifyResponse verify(
            @PathVariable @Pattern(regexp = RegistrationNumber.REGEXP) String rn,
            @RequestHeader(value = "X-Altcha", required = false) String altcha) {
        altchaService.verifyOrThrow(altcha);
        return verification.verify(rn);
    }
}
