package com.str.backend.rn;

import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.auth.internal.InternalUserRepository;
import com.str.backend.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RucniAktServiceTest {

    private final CountyScopeService scope = mock(CountyScopeService.class);
    private final InternalAccessService access = mock(InternalAccessService.class);
    private final InternalUserRepository users = mock(InternalUserRepository.class);
    private final RucniAktService service = new RucniAktService(scope, access, users);
    private final Authentication auth = new TestingAuthenticationToken("x", null);

    /** Županijski je samo opseg COUNTY; ministarstvo i neodređen opseg nisu (isto pravilo kao zaRadnju). */
    @Test
    void zupanijskiSluzbenik_samoOpsegCounty() {
        when(scope.scopeFor(auth)).thenReturn(CountyScope.county("SDŽ", 18L, 17, "Splitsko-dalmatinska županija"));
        org.assertj.core.api.Assertions.assertThat(service.zupanijskiSluzbenik(auth)).isTrue();
        for (CountyScope.Outcome outcome : new CountyScope.Outcome[]{CountyScope.Outcome.MINISTRY, CountyScope.Outcome.NOT_OFFICIAL,
                CountyScope.Outcome.AMBIGUOUS}) {
            when(scope.scopeFor(auth)).thenReturn(CountyScope.all(outcome));
            org.assertj.core.api.Assertions.assertThat(service.zupanijskiSluzbenik(auth)).as(outcome.name()).isFalse();
        }
    }

    @Test
    void zupanijskiSluzbenik_rucniAktSIzdavateljemIzETurizma() {
        when(scope.scopeFor(auth)).thenReturn(CountyScope.county("Splitsko-dalmatinska županija", 18L, 17,
                "Splitsko-dalmatinska županija"));
        when(access.officialOib(auth)).thenReturn(Optional.of("12345678903"));
        when(users.findIzdavatelj("12345678903")).thenReturn(Optional.of(new InternalUserRepository.Izdavatelj(
                "Splitsko-dalmatinska županija", "40781519492", "Upravni odjel za turizam")));

        RucniAkt akt = service.zaRadnju(auth, "334-06/26-01/7", "2181-26-2");

        assertThat(akt).isEqualTo(new RucniAkt("334-06/26-01/7", "2181-26-2", "Splitsko-dalmatinska županija",
                "40781519492", "Upravni odjel za turizam", 17));
    }

    @Test
    void zupanijskiSluzbenik_bezBrojeva_400() {
        when(scope.scopeFor(auth)).thenReturn(CountyScope.county("SDŽ", 18L, 17, "Splitsko-dalmatinska županija"));

        assertThatThrownBy(() -> service.zaRadnju(auth, null, null)).isInstanceOf(BusinessException.class)
                .hasMessage("error.rn.akt.klasa.required");
    }

    /** Neispravan {@code pin} u eTurizmu ne ruši radnju — akt ide bez OIB-a tijela. */
    @Test
    void neispravanPin_bezOib() {
        when(scope.scopeFor(auth)).thenReturn(CountyScope.county("SDŽ", 18L, 17, "Splitsko-dalmatinska županija"));
        when(access.officialOib(auth)).thenReturn(Optional.of("12345678903"));
        when(users.findIzdavatelj("12345678903")).thenReturn(Optional.of(new InternalUserRepository.Izdavatelj(
                "Splitsko-dalmatinska županija", "123", "x".repeat(300))));

        RucniAkt akt = service.zaRadnju(auth, "334", "2181-26-2");

        assertThat(akt.izdavateljOib()).isNull();
        assertThat(akt.izdavateljJedinica()).hasSize(255);
    }

    /** Bez zapisa organizacije naziv izdavatelja dolazi iz opsega, a OIB i jedinica ostaju prazni. */
    @Test
    void zupanijskiSluzbenik_bezOrganizacije_nazivIzOpsega() {
        when(scope.scopeFor(auth)).thenReturn(CountyScope.county("Zadarska županija", 14L, 13, "Zadarska županija"));
        when(access.officialOib(auth)).thenReturn(Optional.of("12345678903"));
        when(users.findIzdavatelj("12345678903")).thenReturn(Optional.empty());

        RucniAkt akt = service.zaRadnju(auth, "334", "2181-26-2");

        assertThat(akt.izdavateljNaziv()).isEqualTo("Zadarska županija");
        assertThat(akt.izdavateljOib()).isNull();
        assertThat(akt.izdavateljZuRb()).isEqualTo(13);
    }

    /** Ministarstvo i neodređen opseg idu kroz eGOP; upisani brojevi se zanemaruju (ni ne provjeravaju). */
    @Test
    void ministarstvoINeodredeno_eGop() {
        when(scope.scopeFor(auth)).thenReturn(CountyScope.ministry("MINTS"));
        assertThat(service.zaRadnju(auth, "nešto", "bez-broja")).isNull();

        when(scope.scopeFor(auth)).thenReturn(CountyScope.all(CountyScope.Outcome.AMBIGUOUS));
        assertThat(service.zaRadnju(auth, null, null)).isNull();
    }
}
