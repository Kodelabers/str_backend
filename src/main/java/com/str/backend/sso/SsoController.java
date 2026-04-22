package com.str.backend.sso;

import com.str.backend.domain.TransitionTrigger;
import com.str.backend.sso.dto.CreateSsoRequest;
import com.str.backend.sso.dto.SsoResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/sso/registracije")
class SsoController {

    private final SsoService ssoService;

    SsoController(SsoService ssoService) {
        this.ssoService = ssoService;
    }

    @PostMapping
    ResponseEntity<SsoResponse> iniciraj(@Valid @RequestBody CreateSsoRequest req) {
        SsoEntity sso = ssoService.iniciraj(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(SsoResponse.from(sso));
    }

    @GetMapping("/{uuidSso}")
    ResponseEntity<SsoResponse> dohvati(@PathVariable UUID uuidSso) {
        return ResponseEntity.ok(SsoResponse.from(ssoService.dohvati(uuidSso)));
    }

    @PostMapping("/{uuidSso}/validacija")
    ResponseEntity<SsoResponse> validiraj(@PathVariable UUID uuidSso) {
        return ResponseEntity.ok(SsoResponse.from(ssoService.validiraj(uuidSso)));
    }

    @PostMapping("/{uuidSso}/callback")
    ResponseEntity<SsoResponse> callback(@PathVariable UUID uuidSso) {
        return ResponseEntity.ok(SsoResponse.from(ssoService.potvrdiCallback(uuidSso)));
    }

    @PostMapping("/{uuidSso}/suspend")
    ResponseEntity<SsoResponse> suspendiraj(@PathVariable UUID uuidSso,
                                            @RequestParam TransitionTrigger razlog) {
        return ResponseEntity.ok(SsoResponse.from(ssoService.suspendiraj(uuidSso, razlog)));
    }

    @PostMapping("/{uuidSso}/povuci")
    ResponseEntity<SsoResponse> povuci(@PathVariable UUID uuidSso) {
        return ResponseEntity.ok(SsoResponse.from(ssoService.povuci(uuidSso)));
    }
}
