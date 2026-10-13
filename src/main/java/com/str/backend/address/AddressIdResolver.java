package com.str.backend.address;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Id-evi adresnog registra za adresu spremljenu nazivima — obrnuto od onoga što radi izdavanje
 * RB-a, koje id-eve s obrasca pretvara u nazive i sprema samo nazive
 * ({@code str_rn.accommodation} id-eve hijerarhije ne čuva).
 *
 * <p>Služi predpopuni obrasca (ponovni zahtjev nakon opoziva): obrazac radi s id-evima, a katastar
 * pri predaji backend čita po {@code kucniBrojId} ({@link CadastreResolver}). Razrješava se niz
 * kaskadu, istim upitima kojima obrazac nudi izbor, a razina se prihvaća samo kad se naziv
 * poklopi s <b>točno jednim</b> zapisom (bez obzira na velika i mala slova i višak bjelina). Prva
 * nerazriješena razina, i sve ispod nje, ostaju {@code null} — tada korisnik adresu bira sam, a
 * nazivi u odgovoru mu pomažu. Pogađanje između dva jednako nazvana zapisa nije dopušteno: krivi
 * kućni broj donio bi tuđi katastar.
 */
@Service
@Transactional(readOnly = true)
public class AddressIdResolver {

    /** Bilo koja komponenta smije biti {@code null} (nije razriješena). */
    public record AddressIds(Long zupanijaId, Long opcinaId, Long naseljeId, Long ulicaId, Long kucniBrojId) {
        public static final AddressIds NONE = new AddressIds(null, null, null, null, null);
    }

    private final CountyRepository countyRepository;
    private final MunicipalityRepository municipalityRepository;
    private final SettlementRepository settlementRepository;
    private final StreetRepository streetRepository;
    private final HouseNumberRepository houseNumberRepository;

    public AddressIdResolver(CountyRepository countyRepository,
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

    public AddressIds resolve(String county, String municipality, String settlement,
                              String street, String houseNumber) {
        // Županija po CountyNames.key — isti ključ kao drugdje (naziv sa ili bez „županija").
        String countyKey = CountyNames.key(county);
        if (countyKey == null) {
            return AddressIds.NONE;
        }
        List<Long> countyIds = countyRepository.findAllByOrderByZuRb().stream()
                .filter(c -> countyKey.equals(CountyNames.key(c.getName())))
                .map(CountyEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Long countyId = countyIds.size() == 1 ? countyIds.get(0) : null;
        if (countyId == null) {
            return AddressIds.NONE;
        }

        Long municipalityId = key(municipality) == null ? null
                : single(municipalityRepository.findByCountyIdOrderByName(countyId, municipality.trim()),
                        key(municipality), MunicipalityEntity::getId, MunicipalityEntity::getName);
        if (municipalityId == null) {
            return new AddressIds(countyId, null, null, null, null);
        }

        Long settlementId = key(settlement) == null ? null
                : single(settlementRepository.findByMunicipalityIdOrderByName(municipalityId, settlement.trim()),
                        key(settlement), SettlementRepository.SettlementProjection::getId,
                        SettlementRepository.SettlementProjection::getName);
        if (settlementId == null) {
            return new AddressIds(countyId, municipalityId, null, null, null);
        }

        Long streetId = key(street) == null ? null
                : single(streetRepository.findBySettlementIdOrderByName(settlementId, street.trim()),
                        key(street), StreetEntity::getId, StreetEntity::getName);
        if (streetId == null) {
            return new AddressIds(countyId, municipalityId, settlementId, null, null);
        }

        Long houseNumberId = key(houseNumber) == null ? null
                : single(houseNumberRepository.findByStreetIdOrderByName(streetId, houseNumber.trim()),
                        key(houseNumber), HouseNumberEntity::getId, HouseNumberEntity::getName);
        return new AddressIds(countyId, municipalityId, settlementId, streetId, houseNumberId);
    }

    /**
     * Id jedinog zapisa čiji se naziv poklapa s {@code key}; {@code null} kad ih nema ili ih je više.
     * Isti id u više redaka (naselje s više poštanskih brojeva) računa se kao jedan.
     */
    private static <T> Long single(List<T> candidates, String key,
                                   Function<T, Long> id, Function<T, String> name) {
        List<Long> ids = candidates.stream()
                .filter(c -> key.equals(key(name.apply(c))))
                .map(id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.size() == 1 ? ids.get(0) : null;
    }

    private static String key(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
