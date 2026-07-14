package com.str.backend.auth;

import com.str.backend.auth.dto.MeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final SessionIdentityResolver identityResolver = mock(SessionIdentityResolver.class);
    private final AuthController controller = new AuthController(authenticationManager, identityResolver);

    /**
     * Regresija: {@code /api/auth/me} je prije slijepo castao principal u {@code LessorPrincipal}
     * i pucao 500 pod NIAS ({@code Saml2Authentication}) sesijom. Sad samo delegira na resolver
     * bez castanja — bilo koji {@link Authentication} prolazi.
     */
    @Test
    void me_delegatesToResolver_withoutCastingPrincipal() {
        Authentication niasSession = mock(Authentication.class);
        MeResponse expected = new MeResponse("NIAS", "12345678901", null, null, "Ivan", "Horvat", "NAJMODAVAC", null);
        when(identityResolver.resolve(niasSession)).thenReturn(expected);

        ResponseEntity<MeResponse> response = controller.me(niasSession);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(expected);
    }
}
