package com.str.backend.rn;

import com.str.backend.common.Zagreb;
import com.str.backend.domain.RnStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.ColumnDefault;
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
    @Column(name = "status", length = 32, nullable = false)
    private RnStatus status;

    @Column(name = "issue_date", nullable = false, updatable = false)
    private LocalDate issueDate;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "suspension_deadline")
    private LocalDate suspensionDeadline;

    /** Rok za očitovanje na prijedlog povlačenja; postavljen samo u statusu WITHDRAWAL_PROPOSED. */
    @Column(name = "withdrawal_deadline")
    private LocalDate withdrawalDeadline;

    /**
     * Povlačenje je predloženo iz {@code SUSPENDED} (T12): broj u {@code WITHDRAWAL_PROPOSED} tada javno
     * ostaje nevaljan, kao suspendiran ({@link #isValidForPublicUse}, {@link #publicStatus}). Izvan
     * prijedloga povlačenja uvijek {@code false}. Obustava i dalje vraća broj u {@code ACTIVE}.
     */
    // DEFAULT kao u changesetu 155: shema iz entiteta (testovi) tada prima i upise koji stupac ne navode.
    @ColumnDefault("false")
    @Column(name = "withdrawal_from_suspended", nullable = false)
    private boolean withdrawalFromSuspended;

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
        // Dan opoziva/povlačenja — od njega teku rok ponovnog zahtjeva i retencija (čl. 5. st. 5).
        // Po hrvatskom vremenu: noćni posao povlači u 01:00, kad je u UTC-u još prethodni dan.
        if (next.isTerminal() && this.validTo == null) {
            this.validTo = Zagreb.today();
        }
        // Reaktivacija, obustava i uvaženi prigovor na povlačenje (REINSTATE) vraćaju broj u važeće
        // stanje, pa dan prestanka više ne vrijedi.
        if (next == RnStatus.ACTIVE && this.validTo != null) {
            this.validTo = null;
        }
        // Svaki rok vrijedi samo u svom prijedlogu; izlaskom iz njega se briše.
        if (next != RnStatus.SUSPENSION_PROPOSED) {
            this.suspensionDeadline = null;
        }
        if (next != RnStatus.WITHDRAWAL_PROPOSED) {
            this.withdrawalDeadline = null;
            this.withdrawalFromSuspended = false;
        }
    }

    void setSuspensionDeadline(LocalDate deadline) {
        this.suspensionDeadline = deadline;
    }

    /** Uz prijelaz u WITHDRAWAL_PROPOSED — {@code RnService#proposeWithdrawal}. */
    void setWithdrawalDeadline(LocalDate deadline) {
        this.withdrawalDeadline = deadline;
    }

    /** Uz prijelaz u WITHDRAWAL_PROPOSED — {@code RnService#proposeWithdrawal}, kad je prethodni status SUSPENDED. */
    void markWithdrawalFromSuspended() {
        this.withdrawalFromSuspended = true;
    }

    /**
     * Smije li se broj oglašavati: {@link RnStatus#isActiveForPublicUse()}, osim prijedloga povlačenja
     * pokrenutog iz suspenzije — takav broj javno ostaje suspendiran.
     */
    public boolean isValidForPublicUse() {
        return status.isActiveForPublicUse() && !isSuspendedWithdrawalProposal();
    }

    /**
     * Status koji vide javnost i SDEP: prijedlog povlačenja iz suspenzije je {@code SUSPENDED}, inače
     * stvarni status.
     */
    public RnStatus publicStatus() {
        return isSuspendedWithdrawalProposal() ? RnStatus.SUSPENDED : status;
    }

    private boolean isSuspendedWithdrawalProposal() {
        return status == RnStatus.WITHDRAWAL_PROPOSED && withdrawalFromSuspended;
    }
}
