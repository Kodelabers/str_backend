package com.str.backend.auth.role;

import com.str.backend.str.StrApplicationUserEntity;
import com.str.backend.str.StrApplicationUserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalUserResolverTest {

    private final StrApplicationUserRepository repository = mock(StrApplicationUserRepository.class);
    private final InternalUserResolver resolver = new InternalUserResolver(repository);

    private static StrApplicationUserEntity user(Boolean internal) {
        StrApplicationUserEntity e = mock(StrApplicationUserEntity.class);
        when(e.getInternal()).thenReturn(internal);
        return e;
    }

    @Test
    void internalTrue_yieldsInternalRole() {
        StrApplicationUserEntity u = user(true);
        when(repository.findFirstByUsernameAndActiveTrue("12345678901")).thenReturn(Optional.of(u));

        assertThat(resolver.isInternal("12345678901")).isTrue();
        assertThat(resolver.resolveRole("12345678901")).isEqualTo(StrRoles.ROLE_INTERNAL);
    }

    @Test
    void internalFalse_yieldsUserRole() {
        StrApplicationUserEntity u = user(false);
        when(repository.findFirstByUsernameAndActiveTrue("12345678901")).thenReturn(Optional.of(u));

        assertThat(resolver.isInternal("12345678901")).isFalse();
        assertThat(resolver.resolveRole("12345678901")).isEqualTo(StrRoles.ROLE_USER);
    }

    @Test
    void internalNull_isTreatedAsUser() {
        StrApplicationUserEntity u = user(null);
        when(repository.findFirstByUsernameAndActiveTrue("12345678901")).thenReturn(Optional.of(u));

        assertThat(resolver.isInternal("12345678901")).isFalse();
        assertThat(resolver.resolveRole("12345678901")).isEqualTo(StrRoles.ROLE_USER);
    }

    @Test
    void notFound_yieldsUserRole() {
        when(repository.findFirstByUsernameAndActiveTrue(any())).thenReturn(Optional.empty());

        assertThat(resolver.isInternal("00000000000")).isFalse();
        assertThat(resolver.resolveRole("00000000000")).isEqualTo(StrRoles.ROLE_USER);
    }

    @Test
    void blankOrNullOib_yieldsUserRole_withoutHittingDb() {
        assertThat(resolver.isInternal(null)).isFalse();
        assertThat(resolver.isInternal("  ")).isFalse();
        assertThat(resolver.resolveRole(null)).isEqualTo(StrRoles.ROLE_USER);
    }

    @Test
    void resolveAuthorities_wrapsRole() {
        StrApplicationUserEntity u = user(true);
        when(repository.findFirstByUsernameAndActiveTrue("12345678901")).thenReturn(Optional.of(u));

        assertThat(resolver.resolveAuthorities("12345678901"))
                .extracting("authority")
                .containsExactly(StrRoles.ROLE_INTERNAL);
    }
}
