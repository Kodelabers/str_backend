package com.str.backend.captcha;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/captcha")
public class AltchaController {

    private final AltchaService altchaService;

    public AltchaController(AltchaService altchaService) {
        this.altchaService = altchaService;
    }

    @GetMapping("/challenge")
    public ChallengeDto challenge() {
        return altchaService.generateChallenge();
    }
}
