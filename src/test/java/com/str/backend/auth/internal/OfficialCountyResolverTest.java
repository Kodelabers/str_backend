package com.str.backend.auth.internal;

import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.auth.internal.InternalUserRepository.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pravilo iz podataka na CDU-u: ministarstvo vidi cijelu RH, organizacija županije svoju županiju
 * (po nadležnosti), sve ostalo cijelu RH.
 */
class OfficialCountyResolverTest {

    private static final String OIB = "12345678903";

    private final InternalUserRepository users = mock(InternalUserRepository.class);
    private final CountyRepository counties = mock(CountyRepository.class);
    private final OfficialCountyResolver resolver = new OfficialCountyResolver(users, counties);

    @Test
    void countyOrganization_withOneCounty_restrictsToIt() {
        organization("Splitsko-dalmatinska županija", "IOT_ZUPANIJA", 1, "17", "Splitsko-dalmatinska županija");
        CountyEntity split = county(2195100017L, 17, "Splitsko-dalmatinska županija");
        when(counties.findFirstByZuRb(17)).thenReturn(Optional.of(split));

        CountyScope scope = resolver.resolve(OIB);

        assertThat(scope.outcome()).isEqualTo(CountyScope.Outcome.COUNTY);
        assertThat(scope.countyId()).isEqualTo(2195100017L);
        assertThat(scope.countyName()).isEqualTo("Splitsko-dalmatinska županija");
        assertThat(scope.subjectName()).isEqualTo("Splitsko-dalmatinska županija");
    }

    /** Na CDU-u organizacija „spl" je po nadležnosti Brodsko-posavska — naziv se ne gleda. */
    @Test
    void countyComesFromJurisdiction_notFromOrganizationName() {
        organization("spl", "IOT_ZUPANIJA", 1, "12", "Brodsko-posavska županija");
        CountyEntity brod = county(12L, 12, "Brodsko-posavska županija");
        when(counties.findFirstByZuRb(12)).thenReturn(Optional.of(brod));

        assertThat(resolver.resolve(OIB).countyName()).isEqualTo("Brodsko-posavska županija");
    }

    /** Bez šifre (ili s nepoznatom) županija se traži po nazivu, u bilo kojem zapisanom obliku. */
    @Test
    void withoutCode_countyIsMatchedByName() {
        organization("Splitsko-dalmatinska županija", "IOT_ZUPANIJA", 1, null, "SPLITSKO-DALMATINSKA ŽUPANIJA");
        CountyEntity zagreb = county(21L, 21, "Grad Zagreb");
        CountyEntity split = county(17L, 17, "Splitsko-dalmatinska županija");
        when(counties.findAllByOrderByZuRb()).thenReturn(List.of(zagreb, split));

        assertThat(resolver.resolve(OIB).zuRb()).isEqualTo(17);
    }

    /** Nadležnost ministarstva na CDU-u pokriva dvije županije, a ono svejedno vidi sve. */
    @Test
    void ministry_isWholeCountry_whateverItsJurisdiction() {
        organization("Ministarstvo turizma i sporta", "IOT_MINTS", 1, "8", "Bjelovarsko-bilogorska županija");

        CountyScope scope = resolver.resolve(OIB);

        assertThat(scope.outcome()).isEqualTo(CountyScope.Outcome.MINISTRY);
        assertThat(scope.restricted()).isFalse();
        assertThat(scope.subjectName()).isEqualTo("Ministarstvo turizma i sporta");
        verifyNoInteractions(counties);
    }

    @Test
    void otherOrganizationType_isWholeCountry() {
        organization("Hrvatska turistička zajednica", "IOT_OSTALO", 0, null, null);

        CountyScope scope = resolver.resolve(OIB);

        assertThat(scope.outcome()).isEqualTo(CountyScope.Outcome.NONE);
        assertThat(scope.restricted()).isFalse();
    }

    @Test
    void countyWithoutJurisdiction_isWholeCountry() {
        organization("Nova županija", "IOT_ZUPANIJA", 0, null, null);

        assertThat(resolver.resolve(OIB).outcome()).isEqualTo(CountyScope.Outcome.NONE);
    }

    @Test
    void countyWithSeveralCounties_isAmbiguous_wholeCountry() {
        organization("Dvije županije", "IOT_ZUPANIJA", 2, "8", "Bjelovarsko-bilogorska županija");

        CountyScope scope = resolver.resolve(OIB);

        assertThat(scope.outcome()).isEqualTo(CountyScope.Outcome.AMBIGUOUS);
        assertThat(scope.restricted()).isFalse();
    }

    @Test
    void withoutOrganization_isWholeCountry() {
        when(users.findOrganizations(OIB)).thenReturn(List.of());

        assertThat(resolver.resolve(OIB).outcome()).isEqualTo(CountyScope.Outcome.NONE);
    }

    @Test
    void unknownCounty_isError_wholeCountry() {
        organization("Splitsko-dalmatinska županija", "IOT_ZUPANIJA", 1, "17", "Nepoznata županija");
        when(counties.findFirstByZuRb(17)).thenReturn(Optional.empty());
        when(counties.findAllByOrderByZuRb()).thenReturn(List.of());

        CountyScope scope = resolver.resolve(OIB);

        assertThat(scope.outcome()).isEqualTo(CountyScope.Outcome.ERROR);
        assertThat(scope.restricted()).isFalse();
    }

    /** Dva aktivna interna zapisa istog OIB-a u različitim organizacijama — ne zna se koja vrijedi. */
    @Test
    void severalOrganizations_areAmbiguous_wholeCountry() {
        when(users.findOrganizations(OIB)).thenReturn(List.of(
                new Organization("Splitsko-dalmatinska županija", "IOT_ZUPANIJA", 1, "17", "Splitsko-dalmatinska županija"),
                new Organization("Grad Zagreb", "IOT_ZUPANIJA", 1, "21", "Grad Zagreb")));

        CountyScope scope = resolver.resolve(OIB);

        assertThat(scope.outcome()).isEqualTo(CountyScope.Outcome.AMBIGUOUS);
        assertThat(scope.restricted()).isFalse();
        verifyNoInteractions(counties);
    }

    /** Greška baze ne smije postati 500 na svakom back-office endpointu. */
    @Test
    void databaseFailure_isError_wholeCountry() {
        when(users.findOrganizations(OIB)).thenThrow(new DataAccessResourceFailureException("baza"));

        assertThat(resolver.resolve(OIB).outcome()).isEqualTo(CountyScope.Outcome.ERROR);
    }

    private void organization(String name, String type, long countyCount, String code, String countyName) {
        when(users.findOrganizations(OIB))
                .thenReturn(List.of(new Organization(name, type, countyCount, code, countyName)));
    }

    /** {@link CountyEntity} ima samo zaštićeni konstruktor (čita se iz rpj_dgu). */
    private static CountyEntity county(Long id, Integer zuRb, String name) {
        CountyEntity county = mock(CountyEntity.class);
        when(county.getId()).thenReturn(id);
        when(county.getZuRb()).thenReturn(zuRb);
        when(county.getName()).thenReturn(name);
        return county;
    }
}
