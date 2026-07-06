package com.str.backend.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Revizijski log administrativnih akcija (brisanje aktivnosti, odobravanje/odbijanje
 * iznajmljivača, retencija). Generic audit trail keyed by free-form action/entity so it
 * is not tied to a single aggregate. Immutable — written once via {@link #record}.
 */
@Entity
@Table(schema = "str_rn", name = "admin_audit_log")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AdminAuditLogEntity {

    @Id
    @Column(name = "audit_id", nullable = false, updatable = false)
    private UUID auditId;

    @Column(name = "actor", length = 128, updatable = false)
    private String actor;

    @Column(name = "action", length = 64, nullable = false, updatable = false)
    private String action;

    @Column(name = "entity_type", length = 64, updatable = false)
    private String entityType;

    @Column(name = "entity_id", length = 64, updatable = false)
    private String entityId;

    @Column(name = "details", length = 1024, updatable = false)
    private String details;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static AdminAuditLogEntity record(String actor, String action, String entityType,
                                             String entityId, String details) {
        AdminAuditLogEntity e = new AdminAuditLogEntity();
        e.auditId = UUID.randomUUID();
        e.actor = actor;
        e.action = action;
        e.entityType = entityType;
        e.entityId = entityId;
        e.details = details;
        e.occurredAt = Instant.now();
        return e;
    }
}
