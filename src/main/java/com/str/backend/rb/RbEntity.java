package com.str.backend.rb;

import com.str.backend.domain.RbStatus;
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
@Table(schema = "str", name = "rb")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class RbEntity {

    @Id
    @Column(name = "rb", length = 18, nullable = false, updatable = false)
    private String rb;

    @Column(name = "id_zahtjeva", updatable = false)
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
        if (next == RbStatus.AKTIVAN && this.datumDo != null) {
            this.datumDo = null;
        }
    }
}
