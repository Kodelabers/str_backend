package com.str.backend.prefill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/registration-prefill")
public class RegistrationPrefillController {

    private final RegistrationPrefillService service;
    private final String frontendBaseUrl;

    public RegistrationPrefillController(RegistrationPrefillService service,
                                         @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.service = service;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @GetMapping
    public ResponseEntity<Void> handoff(@RequestParam String oib,
                                        @RequestParam String ime,
                                        @RequestParam String prezime,
                                        @RequestParam(required = false) Long kucniBrojSifra,
                                        @RequestParam(required = false) Integer brojKreveta,
                                        @RequestParam(required = false) Integer brojGostiju) {
        UUID prefillId = service.store(oib, ime, prezime, kucniBrojSifra, brojKreveta, brojGostiju);
        URI target = UriComponentsBuilder.fromHttpUrl(frontendBaseUrl)
                .path("/registration-number")
                .queryParam("prefill", prefillId)
                .build()
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target.toString())
                .build();
    }

    @GetMapping("/{prefillId}")
    public RegistrationPrefillResponse get(@PathVariable UUID prefillId) {
        return service.resolve(prefillId);
    }
}
