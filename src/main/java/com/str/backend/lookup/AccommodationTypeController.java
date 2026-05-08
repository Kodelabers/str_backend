package com.str.backend.lookup;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sifarnici")
class AccommodationTypeController {

    private final AccommodationTypeRepository repository;

    AccommodationTypeController(AccommodationTypeRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/vrste-objekata")
    @Transactional(readOnly = true)
    ResponseEntity<List<VrstaObjektaResponse>> vrsteObjekata() {
        List<VrstaObjektaResponse> body = repository.findAll().stream()
                .map(e -> new VrstaObjektaResponse(
                        String.valueOf(e.getTypeId()),
                        e.getName(),
                        e.getSkupina()))
                .toList();
        return ResponseEntity.ok(body);
    }
}
