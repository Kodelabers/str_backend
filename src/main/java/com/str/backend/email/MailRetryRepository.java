package com.str.backend.email;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailRetryRepository extends JpaRepository<MailRetryEntity, UUID> {

    @Transactional(readOnly = true)
    Optional<MailRetryEntity> findByKindAndRefId(MailRetryEntity.Kind kind, UUID refId);

    /** Dospjeli zapisi, najstariji rok prvi — {@code pageable} ograničava veličinu serije. */
    @Transactional(readOnly = true)
    List<MailRetryEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            MailRetryEntity.Status status, Instant now, Pageable pageable);
}
