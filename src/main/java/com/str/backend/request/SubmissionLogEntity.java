package com.str.backend.request;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "submission_log")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SubmissionLogEntity {

    @Id
    @Column(name = "log_id", nullable = false, updatable = false)
    private UUID logId;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Column(name = "event_type", length = 32, nullable = false, updatable = false)
    private String eventType;

    @Column(name = "from_status", length = 32, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", length = 32, updatable = false)
    private String toStatus;

    @Column(name = "trigger_name", length = 64, updatable = false)
    private String triggerName;

    @Column(name = "actor", length = 128, updatable = false)
    private String actor;

    @Column(name = "reason", length = 1024, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static SubmissionLogEntity transition(UUID submissionId, String from, String to,
                                                 String trigger, String actor) {
        SubmissionLogEntity e = new SubmissionLogEntity();
        e.logId = UUID.randomUUID();
        e.submissionId = submissionId;
        e.eventType = "STATUS_TRANSITION";
        e.fromStatus = from;
        e.toStatus = to;
        e.triggerName = trigger;
        e.actor = actor;
        e.occurredAt = Instant.now();
        return e;
    }

    public static SubmissionLogEntity validation(UUID submissionId, String step, String outcome,
                                                 String reason) {
        SubmissionLogEntity e = new SubmissionLogEntity();
        e.logId = UUID.randomUUID();
        e.submissionId = submissionId;
        e.eventType = "VALIDATION";
        e.triggerName = step;
        e.toStatus = outcome;
        e.reason = reason;
        e.occurredAt = Instant.now();
        return e;
    }
}
