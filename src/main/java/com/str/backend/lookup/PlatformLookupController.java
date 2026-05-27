package com.str.backend.lookup;

import com.str.backend.accommodation.AccommodationRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/lookups")
@Transactional(readOnly = true)
class PlatformLookupController {

    record PlatformResponse(String id, String naziv, String url) {}

    record CountyResponse(String id, String naziv) {}

    private final OnlinePlatformRepository platformRepository;
    private final AccommodationRepository accommodationRepository;

    PlatformLookupController(OnlinePlatformRepository platformRepository,
                             AccommodationRepository accommodationRepository) {
        this.platformRepository = platformRepository;
        this.accommodationRepository = accommodationRepository;
    }

    @GetMapping("/platforms")
    List<PlatformResponse> getPlatforms() {
        return platformRepository.findByActiveTrueOrderByName().stream()
                .map(p -> new PlatformResponse(String.valueOf(p.getPlatformId()), p.getName(), p.getUrl()))
                .toList();
    }

    /** Counties derived from distinct accommodation.county values — always consistent with activity data. */
    @GetMapping("/counties")
    List<CountyResponse> getCounties() {
        return accommodationRepository.findDistinctCountiesOrderByName().stream()
                .map(name -> new CountyResponse(name, name))
                .toList();
    }
}
