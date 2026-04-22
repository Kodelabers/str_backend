package com.str.backend.iznajmljivac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "iznajmljivac")
public class IznajmljivacEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid_sso", nullable = false, updatable = false)
    private UUID uuidSso;

    @Column(name = "oib", length = 11, nullable = false, updatable = false)
    private String oib;

    @Column(name = "naziv_prezime", nullable = false, updatable = false)
    private String nazivPrezime;

    @Column(name = "adresa_prebivalista", nullable = false, updatable = false)
    private String adresaPrebivalista;

    @Column(name = "is_domacin", nullable = false)
    private boolean isDomacin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IznajmljivacEntity() {
    }

    public static IznajmljivacEntity snapshot(UUID uuidSso, String oib, String nazivPrezime,
                                              String adresaPrebivalista) {
        IznajmljivacEntity e = new IznajmljivacEntity();
        e.uuidSso = uuidSso;
        e.oib = oib;
        e.nazivPrezime = nazivPrezime;
        e.adresaPrebivalista = adresaPrebivalista;
        e.isDomacin = false;
        e.createdAt = Instant.now();
        return e;
    }

    public Long getId() { return id; }
    public UUID getUuidSso() { return uuidSso; }
    public String getOib() { return oib; }
    public String getNazivPrezime() { return nazivPrezime; }
    public String getAdresaPrebivalista() { return adresaPrebivalista; }
    public boolean isDomacin() { return isDomacin; }
    public Instant getCreatedAt() { return createdAt; }

    public void markDomacin(boolean value) {
        this.isDomacin = value;
    }
}
