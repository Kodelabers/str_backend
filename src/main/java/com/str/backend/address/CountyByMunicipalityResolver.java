package com.str.backend.address;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Izvodi županiju iz naziva grada/općine preko DGU registra
 * ({@code rpj_dgu.gradovi_i_opcine → zupanije}).
 *
 * <p>Postoji jer OIB sustav vraća adresu prebivališta bez županije, a GO-1 status domaćina
 * određuje usporedbom županije iznajmljivača sa županijom objekta. Bez ovoga bi svaki iznajmljivač
 * bio {@code host=false}. Rezultat je {@code zupanije.zu_ime} — isti oblik u kojem se sprema
 * županija objekta, pa je usporedba izravna.
 *
 * <p>Usporedba je po nazivu, neosjetljiva na velika/mala slova (OIB sustav piše verzalom, DGU
 * naslovno), uz odbačen prefiks „Grad"/„Općina". Naziv koji pogađa općine u <b>više</b> županija
 * ne daje ništa — pogrešna županija bila bi gora od prazne, jer bi tiho promijenila status domaćina.
 */
@Component
@Transactional(readOnly = true)
public class CountyByMunicipalityResolver {

    private static final List<String> PREFIXES = List.of("grad ", "općina ", "opcina ");

    private final MunicipalityRepository municipalityRepository;
    private final CountyRepository countyRepository;

    public CountyByMunicipalityResolver(MunicipalityRepository municipalityRepository,
                                        CountyRepository countyRepository) {
        this.municipalityRepository = municipalityRepository;
        this.countyRepository = countyRepository;
    }

    public Optional<String> countyOf(String municipalityName) {
        if (municipalityName == null || municipalityName.isBlank()) {
            return Optional.empty();
        }
        List<Integer> counties = municipalityRepository.findByNameIgnoreCase(stripPrefix(municipalityName))
                .stream()
                .map(MunicipalityEntity::getZuRb)
                .distinct()
                .toList();
        if (counties.size() != 1) {
            return Optional.empty();
        }
        return countyRepository.findFirstByZuRb(counties.getFirst()).map(CountyEntity::getName);
    }

    static String stripPrefix(String name) {
        String trimmed = name.trim().replaceAll("\\s+", " ");
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (lower.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return trimmed;
    }
}
