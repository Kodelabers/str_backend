package com.str.backend.auth;

import com.str.backend.auth.dto.MeResponse;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.auth.role.InternalUserResolver;
import com.str.backend.auth.role.StrRoles;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionIdentityResolverTest {

    private LessorRepository lessorRepository;
    private NiasOibResolver niasOibResolver;
    private InternalUserResolver roleResolver;
    private SessionIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        lessorRepository = mock(LessorRepository.class);
        niasOibResolver = mock(NiasOibResolver.class);
        roleResolver = mock(InternalUserResolver.class);
        resolver = new SessionIdentityResolver(lessorRepository, niasOibResolver, roleResolver);
    }

    @Test
    void localPrincipal_resolvesFromLessorEntity() {
        UUID id = UUID.randomUUID();
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(id);
        when(entity.getUsername()).thenReturn("ana.a");
        LessorPrincipal principal = new LessorPrincipal(entity);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(lessorRepository.findById(id)).thenReturn(Optional.of(entity));
        when(entity.getLessorOib()).thenReturn("11111111111");
        when(entity.getFirstName()).thenReturn("Ana");
        when(entity.getLastName()).thenReturn("Anić");
        when(entity.getEmail()).thenReturn("ana@example.hr");

        MeResponse me = resolver.resolve(auth);

        assertThat(me.authType()).isEqualTo("LOCAL");
        assertThat(me.lessorId()).isEqualTo(id);
        assertThat(me.username()).isEqualTo("ana.a");
        assertThat(me.firstName()).isEqualTo("Ana");
        assertThat(me.lastName()).isEqualTo("Anić");
        assertThat(me.oib()).isEqualTo("11111111111");
        assertThat(me.role()).isEqualTo("ROLE_LESSOR");
    }

    @Test
    void localPrincipal_missingLessor_throws401() {
        UUID id = UUID.randomUUID();
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(id);
        LessorPrincipal principal = new LessorPrincipal(entity);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        when(lessorRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(auth))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void niasPrincipal_resolvesFromSamlAttributes_withRoleFromOib() {
        Authentication auth = niasAuth("12345678901", "Ivan", "Horvat");
        // NIAS assertion ne nosi rolu — izvodi se iz OIB-a preko InternalUserResolver.
        when(roleResolver.resolveRole("12345678901")).thenReturn(StrRoles.ROLE_INTERNAL);

        MeResponse me = resolver.resolve(auth);

        assertThat(me.authType()).isEqualTo("NIAS");
        assertThat(me.oib()).isEqualTo("12345678901");
        assertThat(me.firstName()).isEqualTo("Ivan");
        assertThat(me.lastName()).isEqualTo("Horvat");
        assertThat(me.lessorId()).isNull();
        assertThat(me.username()).isNull();
        assertThat(me.role()).isEqualTo(StrRoles.ROLE_INTERNAL);
        // NIAS assertion ne nosi email
        assertThat(me.email()).isNull();
    }

    @Test
    void noSamlIdentity_butMockOib_returnsNiasWithOibOnly() {
        Authentication auth = new UsernamePasswordAuthenticationToken("anon", null);
        when(niasOibResolver.resolve(any())).thenReturn(Optional.of("99999999990"));

        MeResponse me = resolver.resolve(auth);

        assertThat(me.authType()).isEqualTo("NIAS");
        assertThat(me.oib()).isEqualTo("99999999990");
        assertThat(me.firstName()).isNull();
    }

    @Test
    void noIdentityAtAll_throws401() {
        Authentication auth = new UsernamePasswordAuthenticationToken("anon", null);
        when(niasOibResolver.resolve(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(auth))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static Authentication niasAuth(String oib, String first, String last) {
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("oib", List.<Object>of(oib));
        attrs.put("ime", List.<Object>of(first));
        attrs.put("prezime", List.<Object>of(last));
        DefaultSaml2AuthenticatedPrincipal p = new DefaultSaml2AuthenticatedPrincipal("nameid", attrs);
        return new Saml2Authentication(p, "<r/>", List.of());
    }
}
