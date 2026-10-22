package com.str.backend.address;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Izvodi županiju iz šifre ili naziva grada/općine preko DGU registra
 * ({@code rpj_dgu.gradovi_i_opcine → zupanije}). Šifra ({@link #countyOfMunicipalityCode}) je
 * egzaktna i ima prednost; naziv ({@link #countyOf}) je rezerva kad je izvor ne daje.
 *
 * <p>Postoji jer OIB sustav vraća adresu prebivališta bez županije, a GO-1 status domaćina
 * određuje usporedbom županije iznajmljivača sa županijom objekta. Bez ovoga bi svaki iznajmljivač
 * bio {@code host=false}. Rezultat je {@code zupanije.zu_ime} — isti oblik u kojem se sprema
 * županija objekta, pa je usporedba izravna.
 *
 * <p>Usporedba je po nazivu, neosjetljiva na velika/mala slova (OIB sustav piše verzalom, DGU
 * naslovno), i bez prefiksa „Grad"/„Općina" i s njim. Naziv koji pogađa općine u <b>više</b> županija
 * ne daje ništa — pogrešna županija bila bi gora od prazne, jer bi tiho promijenila status domaćina.
 */
@Component
@Transactional(readOnly = true)
public class CountyByMunicipalityResolver {

    private static final List<String> PREFIXES = List.of("grad ", "općina ", "opcina ");
    private static final Pattern MUNICIPALITY_CODE = Pattern.compile("\\d{1,5}");

    private final MunicipalityRepository municipalityRepository;
    private final CountyRepository countyRepository;

    public CountyByMunicipalityResolver(MunicipalityRepository municipalityRepository,
                                        CountyRepository countyRepository) {
        this.municipalityRepository = municipalityRepository;
        this.countyRepository = countyRepository;
    }

    /**
     * Županija po šifri općine ({@code jls_mb}). OIB sustav je šalje uz svaku adresu
     * ({@code sifraOpcine}, npr. „01333" za Grad Zagreb, „03123" za Osijek), pa je to egzaktan put,
     * neovisan o tome kako DGU piše naziv. Kraća šifra se nadopunjuje nulama na 5 znamenki, kako je
     * {@code jls_mb} spremljen.
     */
    public Optional<String> countyOfMunicipalityCode(String municipalityCode) {
        if (municipalityCode == null || !MUNICIPALITY_CODE.matcher(municipalityCode.trim()).matches()) {
            return Optional.empty();
        }
        String code = "0".repeat(Math.max(0, 5 - municipalityCode.trim().length())) + municipalityCode.trim();
        List<Integer> counties = municipalityRepository.findByJlsMb(code).stream()
                .map(MunicipalityEntity::getZuRb)
                .distinct()
                .toList();
        if (counties.size() != 1) {
            return Optional.empty();
        }
        return countyRepository.findFirstByZuRb(counties.getFirst()).map(CountyEntity::getName);
    }

    public Optional<String> countyOf(String municipalityName) {
        if (municipalityName == null || municipalityName.isBlank()) {
            return Optional.empty();
        }
        // I naziv bez prefiksa i puni naziv: DGU većinu piše bez „Grad"/„Općina" (OIB „GRAD SPLIT"
        // → „Split"), ali ne sve — Zagreb je „Grad Zagreb" (CDU, 22.10.2026.), pa bi samo
        // „ZAGREB" ostao bez županije, a sjedište tvrtke iz Zagreba bez adrese.
        String trimmed = municipalityName.trim().replaceAll("\\s+", " ");
        List<Integer> counties = Stream.of(stripPrefix(trimmed), trimmed)
                .distinct()
                .flatMap(name -> municipalityRepository.findByNameIgnoreCase(name).stream())
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
