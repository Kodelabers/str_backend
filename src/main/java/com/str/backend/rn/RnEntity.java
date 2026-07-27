package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "registration_number")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class RnEntity {

    @Id
    @Column(name = "rn", length = 20, nullable = false, updatable = false)
    private String rn;

    @Column(name = "submission_id", updatable = false)
    private UUID submissionId;

    @Column(name = "accommodation_id", nullable = false, updatable = false)
    private UUID accommodationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private RnStatus status;

    @Column(name = "issue_date", nullable = false, updatable = false)
    private LocalDate issueDate;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "suspension_deadline")
    private LocalDate suspensionDeadline;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static RnEntity issue(String rn, UUID submissionId, UUID accommodationId, LocalDate today) {
        RnEntity e = new RnEntity();
        e.rn = rn;
        e.submissionId = submissionId;
        e.accommodationId = accommodationId;
        e.status = RnStatus.ACTIVE;
        e.issueDate = today;
        e.validFrom = today;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    void applyStatus(RnStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
        if (next == RnStatus.WITHDRAWN && this.validTo == null) {
            this.validTo = LocalDate.now();
        }
        if (next == RnStatus.ACTIVE && this.validTo != null) {
            this.validTo = null;
        }
        // Deadline is only meaningful during SUSPENSION_PROPOSED; clear it on exit.
        if (next != RnStatus.SUSPENSION_PROPOSED) {
            this.suspensionDeadline = null;
        }
    }

    void setSuspensionDeadline(LocalDate deadline) {
        this.suspensionDeadline = deadline;
    }
}
