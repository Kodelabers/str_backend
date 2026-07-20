package com.str.backend.lookup;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.guest.GuestRepository;
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

    /** Guest country of residence. The id is the name itself — that is the value the filter takes. */
    record GuestCountryResponse(String id, String naziv) {}

    private final OnlinePlatformRepository platformRepository;
    private final AccommodationRepository accommodationRepository;
    private final GuestRepository guestRepository;

    PlatformLookupController(OnlinePlatformRepository platformRepository,
                             AccommodationRepository accommodationRepository,
                             GuestRepository guestRepository) {
        this.platformRepository = platformRepository;
        this.accommodationRepository = accommodationRepository;
        this.guestRepository = guestRepository;
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

    /**
     * STR-3.2 / spec §2.10: options for the "guest country" filter on the activity report.
     * Distinct values from the reported guests — deliberately not the address-registry country
     * list, which is filtered to non-EU states for lessor registration and would omit most of
     * the countries that actually appear here.
     */
    @GetMapping("/guest-countries")
    List<GuestCountryResponse> getGuestCountries() {
        return guestRepository.findDistinctCountriesOrderByName().stream()
                .map(name -> new GuestCountryResponse(name, name))
                .toList();
    }
}
