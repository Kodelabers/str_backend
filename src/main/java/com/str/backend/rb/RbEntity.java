package com.str.backend.rb;

import com.str.backend.domain.RbStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "rb")
public class RbEntity {

    @Id
    @Column(name = "rb", length = 18, nullable = false, updatable = false)
    private String rb;

    @Column(name = "id_zahtjeva", nullable = false, updatable = false)
    private UUID idZahtjeva;

    @Column(name = "id_sso", nullable = false, updatable = false)
    private UUID idSso;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private RbStatus status;

    @Column(name = "datum_izd", nullable = false, updatable = false)
    private LocalDate datumIzd;

    @Column(name = "datum_od", nullable = false)
    private LocalDate datumOd;

    @Column(name = "datum_do")
    private LocalDate datumDo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RbEntity() {
    }

    public static RbEntity issue(String rb, UUID idZahtjeva, UUID idSso, LocalDate danas) {
        RbEntity e = new RbEntity();
        e.rb = rb;
        e.idZahtjeva = idZahtjeva;
        e.idSso = idSso;
        e.status = RbStatus.AKTIVAN;
        e.datumIzd = danas;
        e.datumOd = danas;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void applyStatus(RbStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
        if (next == RbStatus.POVUCEN && this.datumDo == null) {
            this.datumDo = LocalDate.now();
        }
    }

    public String getRb() { return rb; }
    public UUID getIdZahtjeva() { return idZahtjeva; }
    public UUID getIdSso() { return idSso; }
    public RbStatus getStatus() { return status; }
    public LocalDate getDatumIzd() { return datumIzd; }
    public LocalDate getDatumOd() { return datumOd; }
    public LocalDate getDatumDo() { return datumDo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
