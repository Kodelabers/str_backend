package com.str.backend.zastupnik;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "zastupnik_pravne_osobe")
public class ZastupnikPravneOsobeEntity {

    public static final String IZVOR_SUDSKI_REGISTAR = "SUDSKI_REGISTAR";
    public static final String IZVOR_RUCNI_UNOS = "RUCNI_UNOS";

    @Id
    @Column(name = "id_zastupnika", nullable = false, updatable = false)
    private UUID idZastupnika;

    @Column(name = "id_iznajmljivaca", nullable = false, updatable = false)
    private UUID idIznajmljivaca;

    @Column(name = "oib", length = 11)
    private String oib;

    @Column(name = "ime", length = 128, nullable = false)
    private String ime;

    @Column(name = "prezime", length = 128, nullable = false)
    private String prezime;

    @Column(name = "adresa", length = 255)
    private String adresa;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "telefon", length = 32)
    private String telefon;

    @Column(name = "izvor", length = 32, nullable = false, updatable = false)
    private String izvor;

    @Column(name = "dohvaceno_at", nullable = false, updatable = false)
    private Instant dohvacenoAt;

    protected ZastupnikPravneOsobeEntity() {
    }

    public static ZastupnikPravneOsobeEntity sudskiRegistar(UUID idIznajmljivaca, String oib, String ime,
                                                            String prezime, String adresa) {
        return create(idIznajmljivaca, oib, ime, prezime, adresa, null, null, IZVOR_SUDSKI_REGISTAR);
    }

    public static ZastupnikPravneOsobeEntity rucniUnos(UUID idIznajmljivaca, String oib, String ime,
                                                       String prezime, String adresa, String email, String telefon) {
        return create(idIznajmljivaca, oib, ime, prezime, adresa, email, telefon, IZVOR_RUCNI_UNOS);
    }

    private static ZastupnikPravneOsobeEntity create(UUID idIznajmljivaca, String oib, String ime,
                                                     String prezime, String adresa, String email,
                                                     String telefon, String izvor) {
        ZastupnikPravneOsobeEntity e = new ZastupnikPravneOsobeEntity();
        e.idZastupnika = UUID.randomUUID();
        e.idIznajmljivaca = idIznajmljivaca;
        e.oib = oib;
        e.ime = ime;
        e.prezime = prezime;
        e.adresa = adresa;
        e.email = email;
        e.telefon = telefon;
        e.izvor = izvor;
        e.dohvacenoAt = Instant.now();
        return e;
    }

    public UUID getIdZastupnika() { return idZastupnika; }
    public UUID getIdIznajmljivaca() { return idIznajmljivaca; }
    public String getOib() { return oib; }
    public String getIme() { return ime; }
    public String getPrezime() { return prezime; }
    public String getAdresa() { return adresa; }
    public String getEmail() { return email; }
    public String getTelefon() { return telefon; }
    public String getIzvor() { return izvor; }
    public Instant getDohvacenoAt() { return dohvacenoAt; }
}
