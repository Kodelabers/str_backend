package com.str.backend.address;

import com.str.backend.exception.AddressHierarchyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Provjera da je adresa iz zahtjeva povezan lanac adresnog registra: županija → grad/općina →
 * naselje → ulica → kućni broj (T4: „korisnik ne može odabrati kombinaciju adresnih podataka koja
 * nije međusobno povezana").
 *
 * <p>Frontend kaskadu gradi preko {@code /api/address/**}, ali id-evi putuju kroz zahtjev i mogu se
 * urediti, a razine su se dosad razrješavale svaka za sebe. Veze su iste one kojima kaskada nudi
 * sljedeću razinu (v. CLAUDE.md, string FK-ovi):
 * <ul>
 *   <li>općina ∈ županija: {@code gradovi_i_opcine.zu_rb = zupanije.zu_rb}
 *       ({@link MunicipalityRepository#findByCountyIdOrderByName});</li>
 *   <li>naselje ∈ općina: {@code gradovi_i_opcine.jls_mb = LPAD(naselja.jls_mb, 5, '0')}
 *       ({@link SettlementRepository#findWithPostalCandidates});</li>
 *   <li>ulica ∈ naselje: {@code ar_ulice.naselje_id = naselja.na_mb}
 *       ({@link StreetRepository#findBySettlementIdOrderByName});</li>
 *   <li>kućni broj ∈ ulica: {@code ar_address.ulica_id = ar_ulice.id}
 *       ({@link HouseNumberRepository#findByStreetIdOrderByName}).</li>
 * </ul>
 * Uspoređuje se u Javi nad retcima dohvaćenim po primarnom ključu — bez novog native SQL-a, pa
 * isto radi na H2 i PostgreSQL-u. Sve tablice su tuđe i samo se čitaju.
 *
 * <p>Greška je {@link AddressHierarchyException} (400, {@code details.code}
 * {@code ADDRESS_INCONSISTENT} / {@code ADDRESS_INCOMPLETE}, {@code details.field} polje zahtjeva).
 */
@Service
@Transactional(readOnly = true)
public class AddressHierarchyValidator {

    /** Adresa podnositelja kako je stigla s obrasca: id-evi razina i tekst za ulicu/broj/poštanski. */
    public record AddressSelection(Long countyId, Long municipalityId, Long settlementId,
                                   Long streetId, Long houseNumberId,
                                   String street, String houseNumber, String postalCode) {
    }

    /** Adresa s nazivima iz registra (ulica i broj iz teksta kad nisu odabrani iz registra). */
    public record VerifiedAddress(String county, String municipality, String settlement,
                                  String street, String houseNumber, String postalCode) {
    }

    static final String SUBJECT = "podnositelj.";

    private final CountyRepository countyRepository;
    private final MunicipalityRepository municipalityRepository;
    private final SettlementRepository settlementRepository;
    private final StreetRepository streetRepository;
    private final HouseNumberRepository houseNumberRepository;

    public AddressHierarchyValidator(CountyRepository countyRepository,
                                     MunicipalityRepository municipalityRepository,
                                     SettlementRepository settlementRepository,
                                     StreetRepository streetRepository,
                                     HouseNumberRepository houseNumberRepository) {
        this.countyRepository = countyRepository;
        this.municipalityRepository = municipalityRepository;
        this.settlementRepository = settlementRepository;
        this.streetRepository = streetRepository;
        this.houseNumberRepository = houseNumberRepository;
    }

    /**
     * Adresa podnositelja kad je registar nema i korisnik je upisuje sam.
     *
     * <p>Županija, općina i naselje su obavezni i moraju činiti lanac; nazivi se uzimaju iz
     * registra, ne iz poslanog teksta. Ulica i kućni broj imaju fallback slobodnog unosa (registar
     * ih nema za sva naselja; dopušten je i „bb"): poslan id mora pripadati razini iznad i daje
     * naziv iz registra, a bez id-a vrijedi upisani tekst, koji je tada obavezan. {@code kucniBrojId}
     * bez {@code ulicaId} nije lanac.
     *
     * <p>Poštanski broj mora biti jedan od brojeva koje kaskada nudi za naselje
     * ({@link SettlementRepository#findByMunicipalityIdOrderByName}); kad registar naselju broj ne
     * zna (≈1,4 % naselja), prihvaća se upisani. Neposlan, a jednoznačan broj se dopunjava.
     */
    public VerifiedAddress verifySubjectAddress(AddressSelection a) {
        if (a.countyId() == null) {
            throw AddressHierarchyException.incomplete(SUBJECT + "zupanijaId");
        }
        if (a.municipalityId() == null) {
            throw AddressHierarchyException.incomplete(SUBJECT + "opcinaId");
        }
        if (a.settlementId() == null) {
            throw AddressHierarchyException.incomplete(SUBJECT + "naseljeId");
        }
        CountyEntity county = countyRepository.findById(a.countyId())
                .orElseThrow(() -> AddressHierarchyException.inconsistent(SUBJECT + "zupanijaId"));
        MunicipalityEntity municipality = municipalityRepository.findById(a.municipalityId())
                .filter(m -> inCounty(m, county))
                .orElseThrow(() -> AddressHierarchyException.inconsistent(SUBJECT + "opcinaId"));
        SettlementEntity settlement = settlementRepository.findById(a.settlementId())
                .filter(s -> inMunicipality(s, municipality))
                .orElseThrow(() -> AddressHierarchyException.inconsistent(SUBJECT + "naseljeId"));

        String street;
        if (a.streetId() != null) {
            street = streetRepository.findById(a.streetId())
                    .filter(u -> inSettlement(u, settlement))
                    .map(StreetEntity::getName)
                    .orElseThrow(() -> AddressHierarchyException.inconsistent(SUBJECT + "ulicaId"));
        } else {
            street = requireText(a.street(), SUBJECT + "ulica");
        }

        String houseNumber;
        if (a.houseNumberId() != null) {
            if (a.streetId() == null) {
                throw AddressHierarchyException.incomplete(SUBJECT + "ulicaId");
            }
            houseNumber = houseNumberRepository.findById(a.houseNumberId())
                    .filter(h -> Objects.equals(h.getUlicaId(), a.streetId()))
                    .map(HouseNumberEntity::getName)
                    .orElseThrow(() -> AddressHierarchyException.inconsistent(SUBJECT + "kucniBrojId"));
        } else {
            houseNumber = requireText(a.houseNumber(), SUBJECT + "kucniBroj");
        }

        return new VerifiedAddress(county.getName(), municipality.getName(), settlement.getName(),
                street.trim(), houseNumber.trim(), postalCode(settlement, municipality, a.postalCode()));
    }

    /**
     * Adresa objekta. Provjeravaju se samo razine koje su poslane, svaka prema susjednoj poznatoj:
     * postojeći eTurizam objekt ({@code facilityId}) adresu nosi iz {@code str.*} i id-eve smije
     * ne imati (ne zahtijevaju se), a obavezne razine novog objekta traži validacija zahtjeva.
     *
     * <p>Poslan id mora postojati; za postojeći objekt nebrojčan ili nepoznat id općine/naselja
     * preskače se, kao i dosad ({@code RegistrationService#resolveEntityName} ga sprema kao naziv),
     * a za novi objekt je greška. Nepoznat {@code kucniBrojId} ovdje se preskače — prijavljuje ga
     * {@link CadastreResolver} ({@code error.cadastre.houseNumber.unknown}).
     *
     * @param existingFacility zahtjev nosi {@code facilityId} (objekt iz eTurizma)
     */
    public void verifyFacilityAddress(Long countyId, String cityId, String settlementId, Long houseNumberId,
                                      boolean existingFacility) {
        CountyEntity county = countyId == null ? null
                // Nepoznata županija je već 404 u RegistrationService#countyName; ovo je samo zaštita.
                : countyRepository.findById(countyId)
                        .orElseThrow(() -> AddressHierarchyException.inconsistent("countyId"));
        MunicipalityEntity municipality =
                facilityLevel(cityId, "cityId", existingFacility, municipalityRepository::findById);
        SettlementEntity settlement =
                facilityLevel(settlementId, "settlementId", existingFacility, settlementRepository::findById);

        if (county != null && municipality != null && !inCounty(municipality, county)) {
            throw AddressHierarchyException.inconsistent("cityId");
        }
        if (municipality != null && settlement != null && !inMunicipality(settlement, municipality)) {
            throw AddressHierarchyException.inconsistent("settlementId");
        }
        if (houseNumberId == null) {
            return;
        }
        Optional<HouseNumberEntity> houseNumber = houseNumberRepository.findById(houseNumberId);
        if (houseNumber.isEmpty()) {
            return;
        }
        StreetEntity street = streetRepository.findById(houseNumber.get().getUlicaId())
                .orElseThrow(() -> AddressHierarchyException.inconsistent("kucniBrojId"));
        if (settlement != null) {
            if (!inSettlement(street, settlement)) {
                throw AddressHierarchyException.inconsistent("kucniBrojId");
            }
        } else if (municipality != null) {
            // Naselje nije poslano: kućni broj mora barem ležati u poslanoj općini.
            List<SettlementEntity> streetSettlements = settlementRepository.findByNaMb(street.getNaseljeId());
            if (streetSettlements.stream().noneMatch(s -> inMunicipality(s, municipality))) {
                throw AddressHierarchyException.inconsistent("kucniBrojId");
            }
        }
    }

    private <T> T facilityLevel(String rawId, String field, boolean existingFacility,
                                Function<Long, Optional<T>> finder) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        long id;
        try {
            id = Long.parseLong(rawId.trim());
        } catch (NumberFormatException e) {
            if (existingFacility) {
                return null;
            }
            throw AddressHierarchyException.inconsistent(field);
        }
        Optional<T> found = finder.apply(id);
        if (found.isEmpty() && !existingFacility) {
            throw AddressHierarchyException.inconsistent(field);
        }
        return found.orElse(null);
    }

    /**
     * Poštanski broj naselja istim pravilom kao popis naselja u kaskadi
     * ({@link PostalCodes#preferSameCounty}), da prolazi upravo ono što je korisniku ponuđeno.
     */
    private String postalCode(SettlementEntity settlement, MunicipalityEntity municipality, String submitted) {
        List<SettlementRepository.SettlementPostalRow> rows =
                settlementRepository.findWithPostalCandidates(municipality.getId(), null).stream()
                        .filter(r -> settlement.getId().equals(r.getId()))
                        .toList();
        List<String> offered = PostalCodes.preferSameCounty(rows).stream()
                .map(SettlementRepository.SettlementPostalRow::getPostalCode)
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        String value = blankToNull(submitted);
        if (value == null) {
            if (offered.size() == 1) {
                return offered.get(0);
            }
            throw AddressHierarchyException.incomplete(SUBJECT + "postanskiBroj");
        }
        if (!offered.isEmpty() && !offered.contains(value)) {
            throw AddressHierarchyException.inconsistent(SUBJECT + "postanskiBroj");
        }
        return value;
    }

    static boolean inCounty(MunicipalityEntity municipality, CountyEntity county) {
        return municipality.getZuRb() != null && municipality.getZuRb().equals(county.getZuRb());
    }

    /** {@code gradovi_i_opcine.jls_mb = LPAD(naselja.jls_mb::text, 5, '0')}, kao u kaskadi. */
    static boolean inMunicipality(SettlementEntity settlement, MunicipalityEntity municipality) {
        if (settlement.getJlsMb() == null || municipality.getJlsMb() == null) {
            return false;
        }
        return lpad5(String.valueOf(settlement.getJlsMb())).equals(municipality.getJlsMb().trim());
    }

    static boolean inSettlement(StreetEntity street, SettlementEntity settlement) {
        return street.getNaseljeId() != null && settlement.getNaMb() != null
                && street.getNaseljeId().trim().equals(settlement.getNaMb().trim());
    }

    /** PostgreSQL {@code LPAD(v, 5, '0')}: nadopuna nulama, a dulja vrijednost se reže na 5 znakova. */
    private static String lpad5(String value) {
        if (value.length() >= 5) {
            return value.substring(0, 5);
        }
        return "0".repeat(5 - value.length()) + value;
    }

    private static String requireText(String value, String field) {
        String v = blankToNull(value);
        if (v == null) {
            throw AddressHierarchyException.incomplete(field);
        }
        return v;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
