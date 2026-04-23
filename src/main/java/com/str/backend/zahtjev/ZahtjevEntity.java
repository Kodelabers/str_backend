package com.str.backend.zahtjev;

import com.str.backend.domain.Kanal;
import com.str.backend.domain.ZahtjevStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "zahtjev")
public class ZahtjevEntity {

    @Id
    @Column(name = "id_zahtjeva", nullable = false, updatable = false)
    private UUID idZahtjeva;

    @Column(name = "ur_zahtjeva", length = 64, nullable = false, updatable = false)
    private String urZahtjeva;

    @Column(name = "link_dokumenta", length = 500)
    private String linkDokumenta;

    @Enumerated(EnumType.STRING)
    @Column(name = "kanal", length = 16, nullable = false, updatable = false)
    private Kanal kanal;

    @Column(name = "oznaka_vrste", length = 32, nullable = false, updatable = false)
    private String oznakaVrste;

    @Column(name = "id_iznajmljivaca", nullable = false, updatable = false)
    private UUID idIznajmljivaca;

    @Column(name = "id_nadleznog_tijela")
    private Long idNadleznogTijela;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ZahtjevStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ZahtjevEntity() {
    }

    public static ZahtjevEntity initiate(String urZahtjeva, Kanal kanal, String oznakaVrste,
                                         UUID idIznajmljivaca, Long idNadleznogTijela) {
        ZahtjevEntity e = new ZahtjevEntity();
        e.idZahtjeva = UUID.randomUUID();
        e.urZahtjeva = urZahtjeva;
        e.kanal = kanal;
        e.oznakaVrste = oznakaVrste;
        e.idIznajmljivaca = idIznajmljivaca;
        e.idNadleznogTijela = idNadleznogTijela;
        e.status = ZahtjevStatus.INICIIRAN;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void applyStatus(ZahtjevStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public void setLinkDokumenta(String link) {
        this.linkDokumenta = link;
        this.updatedAt = Instant.now();
    }

    public UUID getIdZahtjeva() { return idZahtjeva; }
    public String getUrZahtjeva() { return urZahtjeva; }
    public String getLinkDokumenta() { return linkDokumenta; }
    public Kanal getKanal() { return kanal; }
    public String getOznakaVrste() { return oznakaVrste; }
    public UUID getIdIznajmljivaca() { return idIznajmljivaca; }
    public Long getIdNadleznogTijela() { return idNadleznogTijela; }
    public ZahtjevStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
