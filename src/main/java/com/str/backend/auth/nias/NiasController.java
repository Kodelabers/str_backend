package com.str.backend.auth.nias;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nias")
public class NiasController {

    @GetMapping("/me")
    public ResponseEntity<NiasMeResponse> me(Authentication authentication) {
        return NiasOibExtractor.extractOib(authentication)
                .map(oib -> ResponseEntity.ok(new NiasMeResponse(oib)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
}
