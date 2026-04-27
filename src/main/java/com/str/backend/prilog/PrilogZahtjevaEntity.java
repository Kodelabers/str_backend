package com.str.backend.prilog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "prilog_zahtjeva")
public class PrilogZahtjevaEntity {

    @Id
    @Column(name = "id_priloga", nullable = false, updatable = false)
    private UUID idPriloga;

    @Column(name = "id_zahtjeva", nullable = false, updatable = false)
    private UUID idZahtjeva;

    @Column(name = "vrsta_priloga", length = 64, nullable = false)
    private String vrstaPriloga;

    @Column(name = "naziv_datoteke", length = 255, nullable = false)
    private String nazivDatoteke;

    @Column(name = "mime_tip", length = 128)
    private String mimeTip;

    @Column(name = "velicina_bajtova")
    private Long velicinaBajtova;

    @Column(name = "uri", length = 1024, nullable = false)
    private String uri;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected PrilogZahtjevaEntity() {
    }

    public static PrilogZahtjevaEntity upload(UUID idZahtjeva, String vrstaPriloga, String nazivDatoteke,
                                              String mimeTip, Long velicinaBajtova, String uri, String sha256) {
        PrilogZahtjevaEntity e = new PrilogZahtjevaEntity();
        e.idPriloga = UUID.randomUUID();
        e.idZahtjeva = idZahtjeva;
        e.vrstaPriloga = vrstaPriloga;
        e.nazivDatoteke = nazivDatoteke;
        e.mimeTip = mimeTip;
        e.velicinaBajtova = velicinaBajtova;
        e.uri = uri;
        e.sha256 = sha256;
        e.uploadedAt = Instant.now();
        return e;
    }

    public UUID getIdPriloga() { return idPriloga; }
    public UUID getIdZahtjeva() { return idZahtjeva; }
    public String getVrstaPriloga() { return vrstaPriloga; }
    public String getNazivDatoteke() { return nazivDatoteke; }
    public String getMimeTip() { return mimeTip; }
    public Long getVelicinaBajtova() { return velicinaBajtova; }
    public String getUri() { return uri; }
    public String getSha256() { return sha256; }
    public Instant getUploadedAt() { return uploadedAt; }
}
