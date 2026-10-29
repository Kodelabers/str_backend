package com.str.backend.auth.internal;

import com.str.backend.accommodation.AccommodationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Opseg se utvrđuje samo za službenika i na svakom pozivu, kao rola — bez keša. */
class CountyScopeServiceTest {

    private static final String ANA = "70000000004";
    private static final CountyScope SPLIT =
            CountyScope.county("Splitsko-dalmatinska županija", 5L, 17, "Splitsko-dalmatinska županija");

    private final Authentication auth = new TestingAuthenticationToken("sluzbenik", null);
    private final InternalAccessService internalAccess = mock(InternalAccessService.class);
    private final OfficialCountyResolver resolver = mock(OfficialCountyResolver.class);
    private final AccommodationRepository accommodations = mock(AccommodationRepository.class);
    private final CountyScopeService service = new CountyScopeService(internalAccess, resolver, accommodations);

    @Test
    void notOfficial_isWholeCountry_withoutResolving() {
        when(internalAccess.officialOib(any())).thenReturn(Optional.empty());

        CountyScope scope = service.scopeFor(auth);

        assertThat(scope.outcome()).isEqualTo(CountyScope.Outcome.NOT_OFFICIAL);
        assertThat(scope.restricted()).isFalse();
        verifyNoInteractions(resolver);
    }

    /** Promjena organizacije u eTurizmu mora djelovati odmah, pa se ništa ne pamti. */
    @Test
    void official_isResolvedOnEveryCall() {
        when(internalAccess.officialOib(any())).thenReturn(Optional.of(ANA));
        when(resolver.resolve(ANA)).thenReturn(SPLIT, CountyScope.ministry("Ministarstvo turizma i sporta"));

        assertThat(service.scopeFor(auth)).isEqualTo(SPLIT);
        assertThat(service.scopeFor(auth).outcome()).isEqualTo(CountyScope.Outcome.MINISTRY);
        verify(resolver, times(2)).resolve(ANA);
    }

    @Test
    void accommodationCounties_unrestricted_isNull() {
        when(internalAccess.officialOib(any())).thenReturn(Optional.of(ANA));
        when(resolver.resolve(ANA)).thenReturn(CountyScope.ministry("Ministarstvo turizma i sporta"));

        assertThat(service.accommodationCounties(auth)).isNull();
        verifyNoInteractions(accommodations);
    }

    @Test
    void accommodationCounties_restricted_returnsStoredVariantsOfTheCounty() {
        when(internalAccess.officialOib(any())).thenReturn(Optional.of(ANA));
        when(resolver.resolve(ANA)).thenReturn(SPLIT);
        when(accommodations.findDistinctCountiesOrderByName()).thenReturn(List.of("Grad Zagreb",
                "SPLITSKO-DALMATINSKA ŽUPANIJA", "Splitsko-dalmatinska", "Splitsko-dalmatinska županija"));

        assertThat(service.accommodationCounties(auth)).containsExactlyInAnyOrder(
                "Splitsko-dalmatinska županija", "SPLITSKO-DALMATINSKA ŽUPANIJA", "Splitsko-dalmatinska");
    }

    /** Ministarstvo bira županiju u filtru: pogađa sve njezine zapisane oblike, kao STR dashboard. */
    @Test
    void requestedCounty_unrestricted_returnsItsStoredVariants() {
        when(internalAccess.officialOib(any())).thenReturn(Optional.of(ANA));
        when(resolver.resolve(ANA)).thenReturn(CountyScope.ministry("Ministarstvo turizma i sporta"));
        when(accommodations.findDistinctCountiesOrderByName()).thenReturn(List.of("Grad Zagreb",
                "SPLITSKO-DALMATINSKA ŽUPANIJA", "Splitsko-dalmatinska"));

        assertThat(service.accommodationCounties(auth, "Splitsko-dalmatinska županija"))
                .containsExactlyInAnyOrder("SPLITSKO-DALMATINSKA ŽUPANIJA", "Splitsko-dalmatinska");
        assertThat(service.accommodationCounties(auth, " ")).isNull();
        assertThat(service.accommodationCounties(auth, null)).isNull();
    }

    /** Ograničenom službeniku tražena županija ne mijenja ništa — ne može njome proširiti opseg. */
    @Test
    void requestedCounty_restricted_isIgnored() {
        when(internalAccess.officialOib(any())).thenReturn(Optional.of(ANA));
        when(resolver.resolve(ANA)).thenReturn(SPLIT);
        when(accommodations.findDistinctCountiesOrderByName()).thenReturn(List.of("Grad Zagreb",
                "Splitsko-dalmatinska županija"));

        assertThat(service.accommodationCounties(auth, "Grad Zagreb"))
                .containsExactly("Splitsko-dalmatinska županija");
    }
}
