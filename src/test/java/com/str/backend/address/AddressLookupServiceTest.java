package com.str.backend.address;

import com.str.backend.address.dto.CountryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressLookupServiceTest {

    @Mock private CountryRepository countryRepository;
    @Mock private CountyRepository countyRepository;
    @Mock private MunicipalityRepository municipalityRepository;
    @Mock private SettlementRepository settlementRepository;
    @Mock private StreetRepository streetRepository;
    @Mock private HouseNumberRepository houseNumberRepository;

    private AddressLookupService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(new NonEuCountryPolicy());
    }

    private AddressLookupService serviceWith(NonEuCountryPolicy policy) {
        return new AddressLookupService(countryRepository, countyRepository, municipalityRepository,
                settlementRepository, streetRepository, houseNumberRepository, policy, false);
    }

    // CountryEntity is @Immutable with no public constructor/factory; build via reflection for tests.
    private static CountryEntity country(long id, String name, String iso2) {
        try {
            CountryEntity e = CountryEntity.class.getDeclaredConstructor().newInstance();
            setField(e, "id", id);
            setField(e, "name", name);
            setField(e, "iso2Alpha", iso2);
            setField(e, "active", true);
            return e;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void setField(CountryEntity e, String field, Object value) throws ReflectiveOperationException {
        Field f = CountryEntity.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(e, value);
    }

    @Test
    void findCountries_excludes_eu_and_efta_members_keeps_rest_and_null_iso() {
        List<CountryEntity> mixed = List.of(
                country(1, "Austrija", "AT"),        // EU -> excluded
                country(2, "Njemačka", "DE"),        // EU -> excluded
                country(3, "Hrvatska", "HR"),        // EU -> excluded
                country(4, "Švicarska", "CH"),       // EFTA -> excluded
                country(5, "Norveška", "NO"),        // EFTA -> excluded
                country(6, "Srbija", "RS"),          // outside EU/EFTA -> kept
                country(7, "Nepoznata", null)        // null iso -> kept
        );
        when(countryRepository.findByActiveTrueOrderByName()).thenReturn(mixed);

        List<CountryResponse> result = service.findCountries(null, CountryPurpose.PREBIVALISTE);

        assertThat(result).extracting(CountryResponse::getName)
                .containsExactly("Srbija", "Nepoznata");
    }

    @Test
    void findCountries_with_query_also_excludes_eu_members() {
        List<CountryEntity> mixed = List.of(
                country(1, "Italija", "IT"),         // EU -> excluded
                country(2, "Albanija", "AL")         // non-EU -> kept
        );
        when(countryRepository.findByActiveTrueAndNameContainingIgnoreCaseOrderByName("a")).thenReturn(mixed);

        List<CountryResponse> result = service.findCountries("a", CountryPurpose.PREBIVALISTE);

        assertThat(result).extracting(CountryResponse::getName)
                .containsExactly("Albanija");
    }

    /** Bez iznimaka sjedište filtrira isto kao prebivalište. */
    @Test
    void findCountries_for_seat_excludes_eu_and_efta_members() {
        when(countryRepository.findByActiveTrueOrderByName()).thenReturn(List.of(
                country(1, "Austrija", "AT"),
                country(2, "Švicarska", "CH"),
                country(3, "Srbija", "RS")));

        List<CountryResponse> result = service.findCountries(null, CountryPurpose.SJEDISTE);

        assertThat(result).extracting(CountryResponse::getName).containsExactly("Srbija");
    }

    /**
     * EU članica bez eIDAS-a ("RO" je samo testni podatak) nudi se za prebivalište,
     * ali ne i za sjedište pravne osobe; ostale EU/EFTA i dalje ispadaju.
     */
    @Test
    void findCountries_offers_eu_state_without_eidas_for_residence_but_not_for_seat() {
        when(countryRepository.findByActiveTrueOrderByName()).thenReturn(List.of(
                country(1, "Rumunjska", "RO"),
                country(2, "Njemačka", "DE"),
                country(3, "Hrvatska", "HR"),
                country(4, "Norveška", "NO"),
                country(5, "Srbija", "RS")));
        AddressLookupService withException = serviceWith(NonEuCountryPolicy.withEuWithoutEidas(Set.of("RO")));

        assertThat(withException.findCountries(null, CountryPurpose.PREBIVALISTE))
                .extracting(CountryResponse::getName).containsExactly("Rumunjska", "Srbija");
        assertThat(withException.findCountries(null, CountryPurpose.SJEDISTE))
                .extracting(CountryResponse::getName).containsExactly("Srbija");
    }
}
