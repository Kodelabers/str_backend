package com.str.backend.aktivnosti;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "sso_aktivnosti")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SsoAktivnostEntity {

    public static final int RETENTION_MONTHS = 18;

    @Id
    @Column(name = "id_aktivnosti", nullable = false, updatable = false)
    private UUID idAktivnosti;

    @Column(name = "id_platforme", nullable = false)
    private Long idPlatforme;

    @Column(name = "rb", length = 18, nullable = false)
    private String rb;

    @Column(name = "id_sso")
    private UUID idSso;

    @Column(name = "razdoblje_od", nullable = false)
    private LocalDate razdobljeOd;

    @Column(name = "razdoblje_do", nullable = false)
    private LocalDate razdobljeDo;

    @Column(name = "broj_nocenja", nullable = false)
    private int brojNocenja;

    @Column(name = "broj_gostiju", nullable = false)
    private int brojGostiju;

    @Column(name = "drzava_gostiju", length = 64)
    private String drzavaGostiju;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "purge_after", nullable = false, updatable = false)
    private Instant purgeAfter;

    public static SsoAktivnostEntity ingest(Long idPlatforme, String rb, UUID idSso,
                                            LocalDate od, LocalDate doDate,
                                            int brojNocenja, int brojGostiju,
                                            String drzavaGostiju) {
        SsoAktivnostEntity e = new SsoAktivnostEntity();
        e.idAktivnosti = UUID.randomUUID();
        e.idPlatforme = idPlatforme;
        e.rb = rb;
        e.idSso = idSso;
        e.razdobljeOd = od;
        e.razdobljeDo = doDate;
        e.brojNocenja = brojNocenja;
        e.brojGostiju = brojGostiju;
        e.drzavaGostiju = drzavaGostiju;
        Instant now = Instant.now();
        e.receivedAt = now;
        e.purgeAfter = now.plus(RETENTION_MONTHS * 30L, ChronoUnit.DAYS);
        return e;
    }
}
