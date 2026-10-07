package com.str.backend.auth;

import com.str.backend.email.event.RegistrationRejectedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RejectedLessorSessionTerminatorTest {

    private static final String USERNAME = "john@example.com";

    /**
     * Repozitorij se registrira kao {@code JdbcIndexedSessionRepository}, kao u produkciji — tako
     * se provjerava i da ga {@code ObjectProvider} s generičkim tipom stvarno nađe. Da ne nađe,
     * sesije odbijenih iznajmljivača tiho bi ostale žive.
     */
    @Test
    void rejection_deletesEverySessionOfThatLessor() {
        JdbcIndexedSessionRepository repository = mock(JdbcIndexedSessionRepository.class);
        doReturn(Map.of("s1", mock(Session.class), "s2", mock(Session.class)))
                .when(repository).findByPrincipalName(USERNAME);

        withTerminator(repository, t -> t.onRejected(event(USERNAME)));

        verify(repository).deleteById("s1");
        verify(repository).deleteById("s2");
    }

    /** Test profil i okoline bez Spring Sessiona: nema repozitorija, nema što gasiti. */
    @Test
    void withoutSpringSession_doesNothing() {
        assertThatCode(() -> withTerminator(null, t -> t.onRejected(event(USERNAME))))
                .doesNotThrowAnyException();
    }

    @Test
    void withoutUsername_touchesNoSession() {
        JdbcIndexedSessionRepository repository = mock(JdbcIndexedSessionRepository.class);

        withTerminator(repository, t -> t.onRejected(event(null)));

        verify(repository, never()).findByPrincipalName(anyString());
    }

    private static void withTerminator(JdbcIndexedSessionRepository repository,
                                       Consumer<RejectedLessorSessionTerminator> action) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            if (repository != null) {
                context.registerBean(JdbcIndexedSessionRepository.class, () -> repository);
            }
            context.registerBean(RejectedLessorSessionTerminator.class);
            context.refresh();
            action.accept(context.getBean(RejectedLessorSessionTerminator.class));
        }
    }

    private static RegistrationRejectedEvent event(String username) {
        return new RegistrationRejectedEvent(UUID.randomUUID(), USERNAME, "John", username);
    }
}
