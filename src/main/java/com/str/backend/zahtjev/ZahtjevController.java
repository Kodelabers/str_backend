package com.str.backend.zahtjev;

import com.str.backend.zahtjev.dto.CreateZahtjevRequest;
import com.str.backend.zahtjev.dto.SsoResponse;
import com.str.backend.zahtjev.dto.ZahtjevResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/zahtjevi")
@Validated
public class ZahtjevController {

    private final ZahtjevService service;

    public ZahtjevController(ZahtjevService service) {
        this.service = service;
    }

    @PostMapping("/init")
    public ResponseEntity<ZahtjevResponse> init(@Valid @RequestBody CreateZahtjevRequest req) {
        ZahtjevEntity z = service.iniciraj(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(z));
    }

    @PostMapping("/{id}/upload-dokument")
    public ZahtjevResponse upload(@PathVariable UUID id,
                                  @RequestParam @NotBlank @Size(max = 500) String link) {
        return toResponse(service.uploadDokument(id, link));
    }

    @PostMapping("/{id}/approve")
    public ZahtjevResponse approve(@PathVariable UUID id) {
        return toResponse(service.approveReferent(id));
    }

    @PostMapping("/{id}/submit")
    public ZahtjevResponse submit(@PathVariable UUID id) {
        return toResponse(service.submit(id));
    }

    @GetMapping("/{id}")
    public ZahtjevResponse get(@PathVariable UUID id) {
        return toResponse(service.dohvati(id));
    }

    private ZahtjevResponse toResponse(ZahtjevEntity z) {
        List<SsoResponse> sso = service.ssoZa(z.getIdZahtjeva()).stream()
                .map(SsoResponse::from)
                .toList();
        return ZahtjevResponse.from(z, sso);
    }
}
