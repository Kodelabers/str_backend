package com.str.backend.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid_sso", nullable = false)
    private UUID uuidSso;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", length = 32)
    private String toStatus;

    @Column(name = "trigger_name", length = 64)
    private String triggerName;

    @Column(name = "outcome", length = 32)
    private String outcome;

    @Column(name = "detail", length = 1024)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLogEntity() {
    }

    public static AuditLogEntity transition(UUID uuidSso, String from, String to, String trigger) {
        AuditLogEntity e = new AuditLogEntity();
        e.uuidSso = uuidSso;
        e.eventType = "STATUS_TRANSITION";
        e.fromStatus = from;
        e.toStatus = to;
        e.triggerName = trigger;
        e.outcome = "APPLIED";
        e.occurredAt = Instant.now();
        return e;
    }

    public static AuditLogEntity validation(UUID uuidSso, String step, String outcome, String detail) {
        AuditLogEntity e = new AuditLogEntity();
        e.uuidSso = uuidSso;
        e.eventType = step;
        e.outcome = outcome;
        e.detail = detail;
        e.occurredAt = Instant.now();
        return e;
    }

    public Long getId() { return id; }
    public UUID getUuidSso() { return uuidSso; }
    public String getEventType() { return eventType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getTriggerName() { return triggerName; }
    public String getOutcome() { return outcome; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
