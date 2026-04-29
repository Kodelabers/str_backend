package com.str.backend.registracija;

import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.registracija.dto.RegistracijaRequest;
import com.str.backend.registracija.dto.RegistracijaResponse;
import com.str.backend.zahtjev.ZahtjevEntity;
import com.str.backend.zahtjev.ZahtjevRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/registracija")
@Validated
public class RegistracijaController {

    private final RegistracijaService service;
    private final ZahtjevRepository zahtjevRepository;

    public RegistracijaController(RegistracijaService service, ZahtjevRepository zahtjevRepository) {
        this.service = service;
        this.zahtjevRepository = zahtjevRepository;
    }

    @PostMapping
    public ResponseEntity<RegistracijaResponse> registriraj(@Valid @RequestBody RegistracijaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registriraj(req));
    }

    @GetMapping(value = "/{idZahtjeva}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> preuzmiPdf(@PathVariable UUID idZahtjeva) {
        ZahtjevEntity z = zahtjevRepository.findById(idZahtjeva)
                .orElseThrow(() -> new ResourceNotFoundException("zahtjev not found: " + idZahtjeva));
        byte[] pdf = z.getPdfSadrzaj();
        if (pdf == null || pdf.length == 0) {
            throw new ResourceNotFoundException("pdf not stored for zahtjev: " + idZahtjeva);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"zahtjev-" + z.getUrZahtjeva().replace('/', '_') + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
