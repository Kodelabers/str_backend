package com.str.backend.rn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "registration_number_log")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class RegistrationNumberLogEntity {

    @Id
    @Column(name = "log_id", nullable = false, updatable = false)
    private UUID logId;

    @Column(name = "rn", length = 20, nullable = false, updatable = false)
    private String rn;

    @Column(name = "from_status", length = 16, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", length = 16, nullable = false, updatable = false)
    private String toStatus;

    @Column(name = "trigger_name", length = 64, nullable = false, updatable = false)
    private String triggerName;

    @Column(name = "actor", length = 128, updatable = false)
    private String actor;

    @Column(name = "reason", length = 1024, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static RegistrationNumberLogEntity transition(String rn, String from, String to,
                                                         String trigger, String actor, String reason) {
        RegistrationNumberLogEntity e = new RegistrationNumberLogEntity();
        e.logId = UUID.randomUUID();
        e.rn = rn;
        e.fromStatus = from;
        e.toStatus = to;
        e.triggerName = trigger;
        e.actor = actor;
        e.reason = reason;
        e.occurredAt = Instant.now();
        return e;
    }
}
