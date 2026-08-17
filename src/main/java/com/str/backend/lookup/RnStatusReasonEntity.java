package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Prezentacijski/administrativni šifrarnik razloga promjene statusa RB-a (suspenzija /
 * povlačenje). Nosi natpis, kontekst, „traži bilješku", aktivnost i redoslijed — <b>ne</b>
 * semantiku prijelaza: koji su razlozi valjani ostaje u kodu ({@code RnTrigger} +
 * {@code RnStatus.canTransitionTo} + {@code RnService.suspend}). {@link #code} je
 * {@code RnTrigger.name()}; redak čija šifra nije u kodom prihvaćenom skupu se u obrascu ignorira.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(schema = "str_rn", name = "rn_status_reason")
public class RnStatusReasonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reason_id")
    private Long reasonId;

    @Column(name = "context", nullable = false, length = 16)
    private String context;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "label_hr", nullable = false, length = 255)
    private String labelHr;

    @Column(name = "requires_note", nullable = false)
    private boolean requiresNote;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Za seed/test konstrukciju; u pogonu redove puni Liquibase, a čitaju se read-only. */
    public static RnStatusReasonEntity of(String context, String code, String labelHr,
                                          boolean requiresNote, boolean active, int sortOrder) {
        RnStatusReasonEntity e = new RnStatusReasonEntity();
        e.context = context;
        e.code = code;
        e.labelHr = labelHr;
        e.requiresNote = requiresNote;
        e.active = active;
        e.sortOrder = sortOrder;
        return e;
    }
}
