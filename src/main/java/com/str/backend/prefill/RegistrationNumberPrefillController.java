package com.str.backend.prefill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/registration-number-prefill")
@Tag(
        name = "Registration Number Prefill",
        description = """
                Handoff za zahtjev za registracijskim brojem (RB) iz vanjskog portala (npr. TuStart).
                Vanjski portal poziva `GET /api/registration-number-prefill` s autenticiranim podacima
                najmoprimca, backend ih trajno pohranjuje i vraća 302 redirect na frontend formu
                s neprovidnim `prefill` UUID-em u query stringu. Frontend potom poziva
                `GET /api/registration-number-prefill/{prefillId}` da popuni formu."""
)
public class RegistrationNumberPrefillController {

    private final RegistrationNumberPrefillService service;
    private final String frontendBaseUrl;

    public RegistrationNumberPrefillController(RegistrationNumberPrefillService service,
                                               @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.service = service;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @GetMapping
    @Operation(
            summary = "Handoff iz vanjskog portala",
            description = """
                    Sprema prefill payload i vraća 302 redirect na frontend formu.
                    `Location` header sadrži `?prefill={uuid}` koji frontend razmjenjuje za pohranjene podatke.""")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "302",
                    description = "Redirect na frontend; payload spremljen, UUID u `Location` headeru.",
                    headers = @io.swagger.v3.oas.annotations.headers.Header(
                            name = "Location",
                            description = "Frontend URL s `prefill` query parametrom",
                            schema = @Schema(type = "string",
                                    example = "http://localhost:3000/registration-number?prefill=4e9c2c6a-2d3e-4a14-8a8f-1c5b5a8e5b71"))),
            @ApiResponse(responseCode = "400",
                    description = "Nedostaje obavezan parametar (`oib`, `firstName`, `lastName`).",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    })
    public ResponseEntity<Void> handoff(
            @Parameter(description = "OIB najmoprimca, 11 znamenki", required = true, example = "12345678901")
            @RequestParam String oib,
            @Parameter(description = "Ime najmoprimca", required = true, example = "Ana")
            @RequestParam String firstName,
            @Parameter(description = "Prezime najmoprimca", required = true, example = "Anić")
            @RequestParam String lastName,
            @Parameter(description = "Šifra kućnog broja iz vanjskog adresnog registra (eturizam_test.ar_address.id). Backend ju razrješava u puni hijerarhijski adresni zapis.",
                    example = "42")
            @RequestParam(required = false) String addressCode,
            @Parameter(description = "Broj kreveta u objektu", example = "3")
            @RequestParam(required = false) Integer maxBedCount,
            @Parameter(description = "Maksimalni broj gostiju u objektu", example = "6")
            @RequestParam(required = false) Integer maxGuestCount) {
        UUID prefillId = service.store(oib, firstName, lastName, addressCode, maxBedCount, maxGuestCount);
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
    @Operation(
            summary = "Dohvat pohranjenog prefill payloada",
            description = """
                    Frontend zove ovaj endpoint nakon redirecta da popuni formu.
                    Adresna šifra je razriješena u puni hijerarhijski zapis (županija → općina → naselje → ulica → kućni broj).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payload uspješno pronađen."),
            @ApiResponse(responseCode = "404",
                    description = "Prefill payload za zadani UUID ne postoji ili je adresna šifra neispravna.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    })
    public RegistrationNumberPrefillResponse get(
            @Parameter(description = "UUID iz `?prefill=` query parametra koji frontend dobiva pri handoff redirectu",
                    required = true,
                    example = "4e9c2c6a-2d3e-4a14-8a8f-1c5b5a8e5b71")
            @PathVariable UUID prefillId) {
        return service.resolve(prefillId);
    }
}
