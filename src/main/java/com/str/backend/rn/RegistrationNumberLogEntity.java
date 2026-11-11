package com.str.backend.rn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Optional;
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

    @Column(name = "from_status", length = 32, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", length = 32, nullable = false, updatable = false)
    private String toStatus;

    @Column(name = "trigger_name", length = 64, nullable = false, updatable = false)
    private String triggerName;

    @Column(name = "actor", length = 128, updatable = false)
    private String actor;

    @Column(name = "reason", length = 1024, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** T16: KLASA ručnog akta županijskog službenika; NULL za akt kroz eGOP. */
    @Column(name = "akt_klasa", length = 64, updatable = false)
    private String aktKlasa;

    @Column(name = "akt_ur_broj", length = 64, updatable = false)
    private String aktUrBroj;

    @Column(name = "akt_izdavatelj_naziv", length = 255, updatable = false)
    private String aktIzdavateljNaziv;

    @Column(name = "akt_izdavatelj_oib", length = 11, updatable = false)
    private String aktIzdavateljOib;

    @Column(name = "akt_izdavatelj_jedinica", length = 255, updatable = false)
    private String aktIzdavateljJedinica;

    @Column(name = "akt_izdavatelj_zu_rb", updatable = false)
    private Integer aktIzdavateljZuRb;

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

    /** Prijelaz čiji akt izdaje županijski službenik bez eGOP-a (T16). */
    public static RegistrationNumberLogEntity transition(String rn, String from, String to, String trigger,
                                                         String actor, String reason, RucniAkt rucni) {
        RegistrationNumberLogEntity e = transition(rn, from, to, trigger, actor, reason);
        if (rucni != null) {
            e.aktKlasa = rucni.klasa();
            e.aktUrBroj = rucni.urBroj();
            e.aktIzdavateljNaziv = rucni.izdavateljNaziv();
            e.aktIzdavateljOib = rucni.izdavateljOib();
            e.aktIzdavateljJedinica = rucni.izdavateljJedinica();
            e.aktIzdavateljZuRb = rucni.izdavateljZuRb();
        }
        return e;
    }

    /** Ručni akt prijelaza (T16); prazno kad akt ide kroz eGOP. */
    public Optional<RucniAkt> rucniAkt() {
        return aktUrBroj == null ? Optional.empty() : Optional.of(new RucniAkt(aktKlasa, aktUrBroj,
                aktIzdavateljNaziv, aktIzdavateljOib, aktIzdavateljJedinica, aktIzdavateljZuRb));
    }
}
