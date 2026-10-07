package com.str.backend.auth;

import com.str.backend.email.event.RegistrationRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * Odbijanje registracije odjavljuje iznajmljivača. Non-EU iznajmljivač se prijavljuje već dok
 * zahtjev čeka pregled, pa bi mu sesija otvorena prije odbijanja inače vrijedila do isteka
 * (30 min neaktivnosti) — a s njom i izdavanje registracijskih brojeva. Ponovnu prijavu
 * sprječava {@link LessorUserDetailsService}.
 *
 * <p>AFTER_COMMIT: sesije se brišu tek kad je {@code REJECTED} u bazi; ranije bi se korisnik u
 * međuvremenu mogao ponovno prijaviti dok je status još {@code PENDING}. Spring Session JDBC
 * indeksira sesije po imenu principala, a ono je za {@link LessorPrincipal} korisničko ime.
 * Na test profilu Spring Session je isključen, pa repozitorija nema i ovdje nema što raditi.
 */
@Component
public class RejectedLessorSessionTerminator {

    private static final Logger log = LoggerFactory.getLogger(RejectedLessorSessionTerminator.class);

    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;

    public RejectedLessorSessionTerminator(
            ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
        this.sessions = sessions;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRejected(RegistrationRejectedEvent event) {
        FindByIndexNameSessionRepository<? extends Session> repository = sessions.getIfAvailable();
        if (repository == null || event.username() == null || event.username().isBlank()) {
            return;
        }
        Set<String> ids = repository.findByPrincipalName(event.username()).keySet();
        ids.forEach(repository::deleteById);
        if (!ids.isEmpty()) {
            log.info("lessor_sessions_terminated lessor={} count={}", event.lessorId(), ids.size());
        }
    }
}
