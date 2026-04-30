package com.str.backend.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(schema = "str_rn", name = "audit_log")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", length = 32, nullable = false)
    private String entityType;

    @Column(name = "entity_id", length = 64, nullable = false)
    private String entityId;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", length = 32)
    private String toStatus;

    @Column(name = "step", length = 16)
    private String step;

    @Column(name = "outcome", length = 32)
    private String outcome;

    @Column(name = "trigger_name", length = 64)
    private String triggerName;

    @Column(name = "detail", length = 1024)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static AuditLogEntity transition(String entityType, String entityId, String from, String to, String trigger) {
        AuditLogEntity e = new AuditLogEntity();
        e.entityType = entityType;
        e.entityId = entityId;
        e.eventType = "STATUS_TRANSITION";
        e.fromStatus = from;
        e.toStatus = to;
        e.triggerName = trigger;
        e.outcome = "APPLIED";
        e.occurredAt = Instant.now();
        return e;
    }

    public static AuditLogEntity validation(String entityType, String entityId, String step, String outcome, String detail) {
        AuditLogEntity e = new AuditLogEntity();
        e.entityType = entityType;
        e.entityId = entityId;
        e.eventType = "VALIDATION";
        e.step = step;
        e.outcome = outcome;
        e.detail = detail;
        e.occurredAt = Instant.now();
        return e;
    }
}
