package com.str.backend.lookup;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/lookups")
class AccommodationTypeController {

    private final AccommodationTypeRepository repository;
    private final ObjectGroupPolicy objectGroupPolicy;

    AccommodationTypeController(AccommodationTypeRepository repository, ObjectGroupPolicy objectGroupPolicy) {
        this.repository = repository;
        this.objectGroupPolicy = objectGroupPolicy;
    }

    /**
     * Vrste za koje se izdaje RB. Uz {@code kategorijaSubjekta} samo vrste iz skupina koje subjekt te
     * vrste smije imati (T13: fizička osoba domaćinstvo, tvrtka ostale ugostiteljske objekte za
     * smještaj) — izbornik na obrascu. Bez nje sve vrste, kao i prije (pregled službenika).
     *
     * <p>Parametar samo sužava izbornik; predaju zahtjeva backend provjerava po sesiji, ne po njemu.
     */
    @GetMapping("/accommodation-types")
    @Transactional(readOnly = true)
    ResponseEntity<List<AccommodationTypeResponse>> getAccommodationTypes(
            @RequestParam(required = false) SubjectCategory kategorijaSubjekta) {
        List<AccommodationTypeEntity> types = kategorijaSubjekta == null
                ? repository.findAllByRegistrationNumberAllowedTrue()
                : objectGroupPolicy.allowedTypes(kategorijaSubjekta);
        List<AccommodationTypeResponse> body = types.stream()
                .map(e -> new AccommodationTypeResponse(
                        String.valueOf(e.getTypeId()),
                        e.getName(),
                        e.getGroup(),
                        e.getCode()))
                .toList();
        return ResponseEntity.ok(body);
    }
}
