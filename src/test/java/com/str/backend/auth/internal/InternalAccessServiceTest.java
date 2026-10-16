package com.str.backend.auth.internal;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalAccessServiceTest {

    private static final String OIB = "12345678903";

    private InternalUserRepository repository;
    private NiasOibResolver niasOibResolver;
    private InternalAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(InternalUserRepository.class);
        niasOibResolver = mock(NiasOibResolver.class);
        service = new InternalAccessService(repository, niasOibResolver, true);
    }

    @Test
    void niasUserWithRole_hasRole() {
        Authentication auth = mock(Authentication.class);
        when(niasOibResolver.resolve(auth)).thenReturn(Optional.of(OIB));
        when(repository.findRoles(OIB)).thenReturn(Set.of(InternalAccessService.RN_MANAGEMENT));

        assertThat(service.hasRole(auth, InternalAccessService.RN_MANAGEMENT)).isTrue();
        assertThat(service.actorFor(auth)).contains("INTERNAL:" + OIB);
    }

    @Test
    void niasUserWithoutRole_hasNoRole() {
        Authentication auth = mock(Authentication.class);
        when(niasOibResolver.resolve(auth)).thenReturn(Optional.of(OIB));
        when(repository.findRoles(OIB)).thenReturn(Set.of("VERIFIER"));

        assertThat(service.hasRole(auth, InternalAccessService.RN_MANAGEMENT)).isFalse();
    }

    @Test
    void noOib_returnsEmpty_withoutQuery() {
        when(niasOibResolver.resolve(any())).thenReturn(Optional.empty());

        assertThat(service.rolesFor(null)).isEmpty();
        assertThat(service.actorFor(null)).isEmpty();
        verify(repository, never()).findRoles(any());
    }

    @Test
    void malformedOib_returnsEmpty_withoutQuery() {
        Authentication auth = mock(Authentication.class);
        for (String bad : new String[]{"1234567890", "123456789031", " 12345678903", "1234567890a", "%"}) {
            when(niasOibResolver.resolve(auth)).thenReturn(Optional.of(bad));
            assertThat(service.rolesFor(auth)).as(bad).isEmpty();
        }
        verify(repository, never()).findRoles(any());
    }

    /** Non-EU iznajmljivač nije interni ni kad local/mock profil podmeće NIAS mock OIB. */
    @Test
    void lessorPrincipal_isNeverInternal() {
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(UUID.randomUUID());
        LessorPrincipal principal = new LessorPrincipal(entity);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        when(niasOibResolver.resolve(auth)).thenReturn(Optional.of(OIB));
        when(repository.findRoles(OIB)).thenReturn(Set.of(InternalAccessService.RN_MANAGEMENT));

        assertThat(service.rolesFor(auth)).isEmpty();
        assertThat(service.actorFor(auth)).isEmpty();
        verify(repository, never()).findRoles(any());
    }

    /**
     * Na mock/demo deployu mock OIB vrijedi za svakog posjetitelja — bez zastavice ne smije dati
     * rolu, inače bi cijeli internet dobio back-office.
     */
    @Test
    void mockOib_withoutFlag_givesNoRole() {
        InternalAccessService strict = new InternalAccessService(repository, niasOibResolver, false);
        when(niasOibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(repository.findRoles(OIB)).thenReturn(Set.of(InternalAccessService.RN_MANAGEMENT));

        assertThat(strict.rolesFor(null)).isEmpty();
        assertThat(strict.rolesFor(mock(Authentication.class))).isEmpty();
        verify(repository, never()).findRoles(any());
    }

    @Test
    void realSamlOib_withoutFlag_stillGivesRole() {
        InternalAccessService strict = new InternalAccessService(repository, niasOibResolver, false);
        Authentication saml = new Saml2Authentication(
                new DefaultSaml2AuthenticatedPrincipal("nameid", Map.of("oib", List.<Object>of(OIB))),
                "<r/>", List.of());
        when(niasOibResolver.resolve(saml)).thenReturn(Optional.of(OIB));
        when(repository.findRoles(OIB)).thenReturn(Set.of(InternalAccessService.RN_MANAGEMENT));

        assertThat(strict.hasRole(saml, InternalAccessService.RN_MANAGEMENT)).isTrue();
    }

    @Test
    void displayNames_internalActorBecomesName_unknownIsMasked_legacyStays() {
        when(repository.findNames(any())).thenReturn(Map.of(OIB, "Ana Službenica"));

        Map<String, String> names = service.displayNames(
                java.util.Arrays.asList("INTERNAL:" + OIB, "INTERNAL:77777777778", "sluzbenik-1", null));

        assertThat(names).containsEntry("INTERNAL:" + OIB, "Ana Službenica")
                .containsEntry("INTERNAL:77777777778", "77*******78")
                .containsEntry("sluzbenik-1", "sluzbenik-1")
                .doesNotContainKey(null);
    }

    /** Nedostupna shema str: OIB kolege se ni tada ne prikazuje. */
    @Test
    void displayNames_lookupFailure_masksOib() {
        when(repository.findNames(any())).thenThrow(new DataAccessResourceFailureException("str nedostupan"));

        assertThat(service.displayNames(List.of("INTERNAL:" + OIB)))
                .containsEntry("INTERNAL:" + OIB, "12*******03");
    }

    @Test
    void effectiveRole_officialIsRnManagement_othersAreUser() {
        Authentication official = mock(Authentication.class);
        Authentication ordinary = mock(Authentication.class);
        when(niasOibResolver.resolve(official)).thenReturn(Optional.of(OIB));
        when(niasOibResolver.resolve(ordinary)).thenReturn(Optional.of("77777777778"));
        when(repository.findRoles(OIB)).thenReturn(Set.of(InternalAccessService.RN_MANAGEMENT));
        when(repository.findRoles("77777777778")).thenReturn(Set.of());

        assertThat(service.effectiveRole(official)).isEqualTo(InternalAccessService.RN_MANAGEMENT);
        assertThat(service.effectiveRole(ordinary)).isEqualTo(InternalAccessService.USER);
        assertThat(service.effectiveRole(null)).isEqualTo(InternalAccessService.USER);
    }

    /** Obični korisnik ne smije ostati bez aplikacije kad je shema str nedostupna. */
    @Test
    void effectiveRole_lookupFailure_isUser() {
        Authentication auth = mock(Authentication.class);
        when(niasOibResolver.resolve(auth)).thenReturn(Optional.of(OIB));
        when(repository.findRoles(OIB)).thenThrow(new DataAccessResourceFailureException("str nedostupan"));

        assertThat(service.effectiveRole(auth)).isEqualTo(InternalAccessService.USER);
    }
}
