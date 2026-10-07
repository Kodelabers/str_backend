package com.str.backend.auth;

import com.str.backend.domain.LessorApplicationStatus;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LessorUserDetailsServiceTest {

    private static final String USERNAME = "john@example.com";

    private final LessorRepository repository = mock(LessorRepository.class);
    private final LessorUserDetailsService service = new LessorUserDetailsService(repository);

    /** Prijava je moguća odmah po registraciji, dok zahtjev još čeka pregled. */
    @ParameterizedTest
    @EnumSource(value = LessorApplicationStatus.class, names = {"PENDING", "ACCEPTED"})
    void pendingAndAccepted_canLogIn(LessorApplicationStatus status) {
        givenLessor(status, "hash");

        assertThat(service.loadUserByUsername(USERNAME).getUsername()).isEqualTo(USERNAME);
    }

    @Test
    void rejected_cannotLogIn() {
        givenLessor(LessorApplicationStatus.REJECTED, "hash");

        assertThatThrownBy(() -> service.loadUserByUsername(USERNAME))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void withoutPassword_cannotLogIn() {
        givenLessor(LessorApplicationStatus.ACCEPTED, null);

        assertThatThrownBy(() -> service.loadUserByUsername(USERNAME))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    private void givenLessor(LessorApplicationStatus status, String passwordHash) {
        LessorEntity lessor = mock(LessorEntity.class);
        when(lessor.getLessorId()).thenReturn(UUID.randomUUID());
        when(lessor.getUsername()).thenReturn(USERNAME);
        when(lessor.getPasswordHash()).thenReturn(passwordHash);
        when(lessor.getApplicationStatus()).thenReturn(status);
        when(repository.findByUsername(USERNAME)).thenReturn(Optional.of(lessor));
    }
}
