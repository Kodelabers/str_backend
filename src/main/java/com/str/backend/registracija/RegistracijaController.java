package com.str.backend.registracija;

import com.str.backend.registracija.dto.RegistracijaRequest;
import com.str.backend.registracija.dto.RegistracijaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/registracija")
@Validated
public class RegistracijaController {

    private final RegistracijaService service;

    public RegistracijaController(RegistracijaService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RegistracijaResponse> registriraj(@Valid @RequestBody RegistracijaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registriraj(req));
    }
}
