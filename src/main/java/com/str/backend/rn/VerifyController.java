package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.dto.VerifyResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/verify")
public class VerifyController {

    private final RnRepository rnRepository;
    private final AccommodationRepository accommodationRepository;
    private final VerifyMapper mapper;

    public VerifyController(RnRepository rnRepository, AccommodationRepository accommodationRepository,
                            VerifyMapper mapper) {
        this.rnRepository = rnRepository;
        this.accommodationRepository = accommodationRepository;
        this.mapper = mapper;
    }

    @GetMapping("/{rn}")
    @Transactional(readOnly = true)
    public VerifyResponse verify(@PathVariable String rn) {
        RnEntity entity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        AccommodationEntity accommodation = accommodationRepository.findById(entity.getAccommodationId())
                .orElseThrow(() -> new ResourceNotFoundException("accommodation not found: " + entity.getAccommodationId()));
        return mapper.toResponse(entity, accommodation);
    }
}
