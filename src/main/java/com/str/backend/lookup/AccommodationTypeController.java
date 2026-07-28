package com.str.backend.lookup;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/lookups")
class AccommodationTypeController {

    private final AccommodationTypeRepository repository;

    AccommodationTypeController(AccommodationTypeRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/accommodation-types")
    @Transactional(readOnly = true)
    ResponseEntity<List<AccommodationTypeResponse>> getAccommodationTypes() {
        List<AccommodationTypeResponse> body = repository.findAllByRegistrationNumberAllowedTrue().stream()
                .map(e -> new AccommodationTypeResponse(
                        String.valueOf(e.getTypeId()),
                        e.getName(),
                        e.getGroup(),
                        e.getCode()))
                .toList();
        return ResponseEntity.ok(body);
    }
}
